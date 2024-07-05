/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zaxxer.hikari.util;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.zaxxer.hikari.util.ClockSource.currentTime;
import static com.zaxxer.hikari.util.ClockSource.elapsedNanos;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.*;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

   // 整个连接池
   private final CopyOnWriteArrayList<T> sharedList;
   private final boolean weakThreadLocals;

   // 当前线程的连接池
   private final ThreadLocal<List<Object>> threadList;
   private final IBagStateListener listener;
   private final AtomicInteger waiters;
   private volatile boolean closed;

   // handoff：传球，传递。此处表达的含义是生产者传递过来的连接
   private final SynchronousQueue<T> handoffQueue;

   public interface IConcurrentBagEntry
   {
      int STATE_NOT_IN_USE = 0;
      int STATE_IN_USE = 1;
      int STATE_REMOVED = -1;
      int STATE_RESERVED = -2;

      boolean compareAndSet(int expectState, int newState);
      void setState(int newState);
      int getState();
   }

   public interface IBagStateListener
   {
      void addBagItem(int waiting);
   }

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      this.listener = listener;
      this.weakThreadLocals = useWeakThreadLocals();

      this.handoffQueue = new SynchronousQueue<>(true);
      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      if (weakThreadLocals) {
         this.threadList = ThreadLocal.withInitial(() -> new ArrayList<>(16));
      } else {
         this.threadList = ThreadLocal.withInitial(() -> new FastList<>(IConcurrentBagEntry.class, 16));
      }
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      // Try the thread-local list first
      // 首先从thread-local里面获取连接
      final var list = threadList.get();
      // 尾遍历的好处在于性能比较强，否则复制的时候太浪费性能
      /**
       * 按照此处理解，当 threadLocal 里面用的是 ArrayList 时，里面的元素是 bagEntry 的 WeakReference
       * 而是 FastList 时，里面直接放的就是 bagEntry
       */
      for (int i = list.size() - 1; i >= 0; i--) {
         final var entry = list.remove(i);
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         // 使用 cas 进行状态修改，减少性能损耗
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // Otherwise, scan the shared list ... then poll the handoff queue
      final int waiting = waiters.incrementAndGet();
      try {
         for (T bagEntry : sharedList) {
            if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               // If we may have stolen another waiter's connection, request another bag add.
               /**
                * 根据注释，说明 listener 的 addBagItem 是指需要创建新的连接个数
                * 此时的 waiter 可能获取的是其他 waiter 通知创建的连接，所以在获得连接之后需要判断下，自己是不是唯一连接
                * 也就是判断当前线程是不是唯一的waiter，如果不是，那么还得通知创建新的连接
                * 有点类似 ReentrantLock 里的非公平锁了，过来先抢一个
                 */

               if (waiting > 1) {
                  listener.addBagItem(waiting - 1);
               }
               return bagEntry;
            }
         }

         listener.addBagItem(waiting);

         timeout = timeUnit.toNanos(timeout);
         do {
            final var start = currentTime();
            final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
            // 此处的等待创建连接是可以接受 timeout 时，还是拿到 null 的
            if (bagEntry == null || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
               return bagEntry;
            }

            timeout -= elapsedNanos(start);
         } while (timeout > 10_000);

         // 还是可能拿不到生产者创建新连接
         return null;
      }
      finally {
         /**
          * 如果没获取到连接，把自己从waiter队列里删除
          */
         waiters.decrementAndGet();
      }
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    * requite：回报
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the bagEntry was not borrowed from the bag
    * 归还连接
    */
   public void requite(final T bagEntry)
   {
      /**
       * 此处不用cas或者加锁的原因是因为该连接被当前线程获取了，只有当前线程能够操作该连接为 not in use
       * 其他线程操作时都会通过线程安全的cas方法试图获取该线程，所以当前线程操作该连接一定是线程安全的
       */
      bagEntry.setState(STATE_NOT_IN_USE);

      // 遍历当前的waiter
      for (var i = 0; waiters.get() > 0; i++) {
         /**
          * 两种情况：
          * 1. 如果连接被其他线程抢走了
          * 2. 如果没被抢走，就把该连接放到 handoffQueue 里，提供给其他 waiter
          * 当然可能返回false，譬如当前没有线程在遍历 sharedList，也没有线程在 poll 我们的 handoffQueue
          * 当waiters.get()大于0，代表存在等待者，所以继续下一次循环
          */
         if (bagEntry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(bagEntry)) {
            return;
         }
         else if ((i & 0xff) == 0xff) {
            parkNanos(MICROSECONDS.toNanos(10));
         }
         else {
            Thread.yield();
         }
      }
      /**
       * 如果走到这儿了，说明连接状态改为 not in use了，且没有waiter等待该连接
       * 那就把该连接放到当前线程的 threadLocal 里，方便下次直接用，这样获取到可用连接的概率大
       */

      final var threadLocalList = threadList.get();
      if (threadLocalList.size() < 50) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }
   }

   /**
    * Add a new object to the bag for others to borrow.
    *
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);

      // spin until a thread takes it or none are waiting
      while (waiters.get() > 0 && bagEntry.getState() == STATE_NOT_IN_USE && !handoffQueue.offer(bagEntry)) {
         Thread.yield();
      }
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    *
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      threadList.get().remove(bagEntry);

      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    *
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final var list = sharedList.stream().filter(e -> e.getState() == state).collect(Collectors.toList());
      Collections.reverse(list);
      return list;
   }

   /**
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    *
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * @param bagEntry the item to unreserve
    */
   @SuppressWarnings("SpellCheckingInspection")
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         // spin until a thread takes it or none are waiting
         while (waiters.get() > 0 && !handoffQueue.offer(bagEntry)) {
            Thread.yield();
         }
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * @return the number of threads waiting for items from the bag
    */
   public int getWaitingThreadCount()
   {
      return waiters.get();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      var count = 0;
      for (var e : sharedList) {
         if (e.getState() == state) {
            count++;
         }
      }
      return count;
   }

   public int[] getStateCounts()
   {
      final var states = new int[6];
      for (var e : sharedList) {
         ++states[e.getState()];
      }
      states[4] = sharedList.size();
      states[5] = waiters.get();

      return states;
   }

   /**
    * Get the total number of items in the bag.
    *
    * @return the number of items in the bag
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      sharedList.forEach(entry -> LOGGER.info(entry.toString()));
   }

   /**
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    *
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
