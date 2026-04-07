package dev.mapnhud.client.map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Holds the scan-related queues that the chunk cache feeds in lockstep:
 * <ul>
 *   <li>{@code priorityScanQueue} — near-player cave loads, drained first</li>
 *   <li>{@code scanQueue} — vanilla {@code ChunkEvent.Load} arrivals</li>
 *   <li>{@code refloodQueue} — chunks that need a re-scan after a new flood completes</li>
 * </ul>
 *
 * <p>Each queue has a paired dedup set so chunks aren't scheduled twice.
 *
 * <p>Eligibility ticks let the cache delay re-scans (for chunks waiting on
 * upgrade to {@code LevelChunk}) without busy-spinning.
 */
public final class ChunkScanQueueSet {

  public record QueuedChunk(ChunkAccess chunk, int attempts, int eligibleTick, boolean priority) {}
  public record QueuedRefloodChunk(long chunkKey, int layerY, int attempts, int eligibleTick) {}

  /** Outcome of a requeue attempt. */
  public enum RequeueOutcome { REQUEUED, DROPPED }

  private final ArrayDeque<QueuedChunk> priorityScanQueue = new ArrayDeque<>();
  private final ArrayDeque<QueuedChunk> scanQueue = new ArrayDeque<>();
  private final LongOpenHashSet scanQueued = new LongOpenHashSet();

  private final ArrayDeque<QueuedRefloodChunk> refloodQueue = new ArrayDeque<>();
  private final HashSet<RefloodKey> refloodQueued = new HashSet<>();

  // Cached "next eligible tick" per queue, recomputed on insert/poll.
  private int nextPriorityEligibleTick = Integer.MAX_VALUE;
  private int nextScanEligibleTick = Integer.MAX_VALUE;
  private int nextRefloodEligibleTick = Integer.MAX_VALUE;

  // -- Mutation --

  public void clear() {
    priorityScanQueue.clear();
    scanQueue.clear();
    scanQueued.clear();
    refloodQueue.clear();
    refloodQueued.clear();
    nextPriorityEligibleTick = Integer.MAX_VALUE;
    nextScanEligibleTick = Integer.MAX_VALUE;
    nextRefloodEligibleTick = Integer.MAX_VALUE;
  }

  /**
   * Enqueues a chunk for scanning. Returns false if the chunk was already
   * queued (deduped).
   */
  public boolean enqueueScan(ChunkAccess chunk, int tickCounter, boolean priority) {
    long key = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
    if (!scanQueued.add(key)) return false;

    QueuedChunk q = new QueuedChunk(chunk, 0, tickCounter, priority);
    if (priority) {
      priorityScanQueue.addLast(q);
      if (tickCounter < nextPriorityEligibleTick) nextPriorityEligibleTick = tickCounter;
    } else {
      scanQueue.addLast(q);
      if (tickCounter < nextScanEligibleTick) nextScanEligibleTick = tickCounter;
    }
    return true;
  }

  /** Returns true if a chunk position is already in the scan queue. */
  public boolean containsScan(int chunkX, int chunkZ) {
    return scanQueued.contains(ChunkPos.asLong(chunkX, chunkZ));
  }

  public boolean enqueueReflood(long chunkKey, int layerY, int tickCounter) {
    RefloodKey key = new RefloodKey(chunkKey, layerY);
    if (!refloodQueued.add(key)) return false;
    refloodQueue.addLast(new QueuedRefloodChunk(chunkKey, layerY, 0, tickCounter));
    if (tickCounter < nextRefloodEligibleTick) nextRefloodEligibleTick = tickCounter;
    return true;
  }

  /** Removes the chunk from all dedup sets when it unloads. */
  public void onChunkUnload(int chunkX, int chunkZ) {
    long key = ChunkPos.asLong(chunkX, chunkZ);
    scanQueued.remove(key);
    refloodQueue.removeIf(q -> q.chunkKey() == key);
    refloodQueued.removeIf(refloodKey -> refloodKey.chunkKey() == key);
  }

  // -- Poll --

  /** Polls the next eligible scan chunk; priority queue first. */
  public QueuedChunk pollNextScanChunk(int tickCounter) {
    if (priorityScanQueue.isEmpty() && scanQueue.isEmpty()) return null;

    QueuedChunk q = pollEligible(priorityScanQueue, tickCounter, true);
    if (q != null) return q;
    return pollEligible(scanQueue, tickCounter, false);
  }

  private QueuedChunk pollEligible(ArrayDeque<QueuedChunk> queue, int tickCounter, boolean priority) {
    if (queue.isEmpty()) return null;
    if (priority && tickCounter < nextPriorityEligibleTick) return null;
    if (!priority && tickCounter < nextScanEligibleTick) return null;

    int size = queue.size();
    int newMinEligible = Integer.MAX_VALUE;
    QueuedChunk found = null;
    for (int i = 0; i < size; i++) {
      QueuedChunk queued = queue.pollFirst();
      if (queued == null) break;
      if (found == null && queued.eligibleTick() <= tickCounter) {
        found = queued;
        continue;
      }
      if (queued.eligibleTick() < newMinEligible) newMinEligible = queued.eligibleTick();
      queue.addLast(queued);
    }
    if (priority) {
      nextPriorityEligibleTick = newMinEligible;
    } else {
      nextScanEligibleTick = newMinEligible;
    }
    return found;
  }

  /** Marks a chunk's scan slot as resolved (removes from dedup). */
  public void markScanResolved(int chunkX, int chunkZ) {
    scanQueued.remove(ChunkPos.asLong(chunkX, chunkZ));
  }

  /**
   * Re-enqueues an unresolved scan with backoff. Returns DROPPED when the
   * chunk has exceeded {@code maxRetries}; otherwise REQUEUED.
   */
  public RequeueOutcome requeueUnresolvedScan(QueuedChunk queued, int tickCounter,
                                              int maxRetries, int maxRetryDelayTicks) {
    int nextAttempt = queued.attempts() + 1;
    if (nextAttempt > maxRetries) {
      markScanResolved(queued.chunk().getPos().x, queued.chunk().getPos().z);
      return RequeueOutcome.DROPPED;
    }

    int delay = Math.min(maxRetryDelayTicks, 1 << Math.min(nextAttempt - 1, 4));
    int eligible = tickCounter + delay;
    QueuedChunk next = new QueuedChunk(queued.chunk(), nextAttempt, eligible, queued.priority());
    if (queued.priority()) {
      priorityScanQueue.addLast(next);
      if (eligible < nextPriorityEligibleTick) nextPriorityEligibleTick = eligible;
    } else {
      scanQueue.addLast(next);
      if (eligible < nextScanEligibleTick) nextScanEligibleTick = eligible;
    }
    return RequeueOutcome.REQUEUED;
  }

  public QueuedRefloodChunk pollNextReflood(int tickCounter) {
    if (refloodQueue.isEmpty()) return null;
    if (tickCounter < nextRefloodEligibleTick) return null;

    int size = refloodQueue.size();
    int newMinEligible = Integer.MAX_VALUE;
    QueuedRefloodChunk found = null;
    for (int i = 0; i < size; i++) {
      QueuedRefloodChunk queued = refloodQueue.pollFirst();
      if (queued == null) break;
      if (found == null && queued.eligibleTick() <= tickCounter) {
        found = queued;
        refloodQueued.remove(new RefloodKey(queued.chunkKey(), queued.layerY()));
        continue;
      }
      if (queued.eligibleTick() < newMinEligible) newMinEligible = queued.eligibleTick();
      refloodQueue.addLast(queued);
    }
    nextRefloodEligibleTick = newMinEligible;
    return found;
  }

  public boolean refloodIsEmpty() {
    return refloodQueue.isEmpty();
  }

  /**
   * Re-enqueues an unresolved reflood with backoff. Returns DROPPED when the
   * chunk has exceeded {@code maxRetries}; otherwise REQUEUED.
   */
  public RequeueOutcome requeueUnresolvedReflood(QueuedRefloodChunk queued, int tickCounter,
                                                 int maxRetries, int maxRetryDelayTicks) {
    int nextAttempt = queued.attempts() + 1;
    if (nextAttempt > maxRetries) {
      return RequeueOutcome.DROPPED;
    }

    int delay = Math.min(maxRetryDelayTicks, 1 << Math.min(nextAttempt - 1, 4));
    int eligible = tickCounter + delay;
    RefloodKey key = new RefloodKey(queued.chunkKey(), queued.layerY());
    if (!refloodQueued.add(key)) return RequeueOutcome.REQUEUED;
    refloodQueue.addLast(new QueuedRefloodChunk(
        queued.chunkKey(), queued.layerY(), nextAttempt, eligible));
    if (eligible < nextRefloodEligibleTick) nextRefloodEligibleTick = eligible;
    return RequeueOutcome.REQUEUED;
  }

  // -- Sizes / introspection --

  public int priorityQueueSize() { return priorityScanQueue.size(); }
  public int scanQueueSize() { return scanQueue.size(); }
  public int refloodQueueSize() { return refloodQueue.size(); }
  public int scanQueuedSize() { return scanQueued.size(); }
  public int navQueueSize() { return 0; }
  public int navQueuedSize() { return 0; }

  public int nextPriorityEligibleTick() { return nextPriorityEligibleTick; }
  public int nextScanEligibleTick() { return nextScanEligibleTick; }

  private record RefloodKey(long chunkKey, int layerY) {}
}
