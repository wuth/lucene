/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.queries.intervals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.MatchesUtils;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.util.PriorityQueue;

class MinimumShouldMatchIntervalsSource extends IntervalsSource {

  private final IntervalsSource[] sources;
  private final int minShouldMatch;

  MinimumShouldMatchIntervalsSource(IntervalsSource[] sources, int minShouldMatch) {
    assert minShouldMatch < sources.length;
    this.sources = sources;
    this.minShouldMatch = minShouldMatch;
  }

  @Override
  public IntervalIterator intervals(String field, LeafReaderContext ctx) throws IOException {
    List<IntervalIterator> iterators = new ArrayList<>();
    for (IntervalsSource source : sources) {
      IntervalIterator it = source.intervals(field, ctx);
      if (it != null) {
        iterators.add(it);
      }
    }
    if (iterators.size() < minShouldMatch) {
      return null;
    }
    return new MinimumShouldMatchIntervalIterator(iterators, minShouldMatch, () -> {});
  }

  @Override
  public IntervalMatchesIterator matches(String field, LeafReaderContext ctx, int doc)
      throws IOException {
    Map<IntervalIterator, CachingMatchesIterator> lookup = new IdentityHashMap<>();
    for (IntervalsSource source : sources) {
      IntervalMatchesIterator mi = source.matches(field, ctx, doc);
      if (mi != null) {
        CachingMatchesIterator cmi = new CachingMatchesIterator(mi);
        lookup.put(IntervalMatches.wrapMatches(cmi, doc), cmi);
      }
    }
    if (lookup.size() < minShouldMatch) {
      return null;
    }
    MinimumShouldMatchIntervalIterator it =
        new MinimumShouldMatchIntervalIterator(
            lookup.keySet(),
            minShouldMatch,
            MinimizingConjunctionIntervalsSource.cacheIterators(lookup.values()));
    if (it.advance(doc) != doc) {
      return null;
    }
    if (it.nextInterval() == IntervalIterator.NO_MORE_INTERVALS) {
      return null;
    }
    return new MinimumMatchesIterator(it, lookup);
  }

  @Override
  public void visit(String field, QueryVisitor visitor) {
    Query parent = new IntervalQuery(field, this);
    QueryVisitor v = visitor.getSubVisitor(BooleanClause.Occur.SHOULD, parent);
    for (IntervalsSource source : sources) {
      source.visit(field, v);
    }
  }

  @Override
  public int minExtent() {
    int[] subExtents = new int[sources.length];
    for (int i = 0; i < subExtents.length; i++) {
      subExtents[i] = sources[i].minExtent();
    }
    Arrays.sort(subExtents);
    int minExtent = 0;
    for (int i = 0; i < minShouldMatch; i++) {
      minExtent += subExtents[i];
    }
    return minExtent;
  }

  @Override
  public Collection<IntervalsSource> pullUpDisjunctions() {
    return Collections.singleton(this);
  }

  @Override
  public String toString() {
    return "AtLeast("
        + Arrays.stream(sources).map(IntervalsSource::toString).collect(Collectors.joining(","))
        + "~"
        + minShouldMatch
        + ")";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MinimumShouldMatchIntervalsSource that = (MinimumShouldMatchIntervalsSource) o;
    return minShouldMatch == that.minShouldMatch && Arrays.equals(sources, that.sources);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(minShouldMatch);
    result = 31 * result + Arrays.hashCode(sources);
    return result;
  }

  // This works as a combination of unordered-AND and OR
  // First of all, iterators are advanced using a DisjunctionDISIApproximation
  // Once positioned on a document, nextInterval() is called on each interval, and
  // those that have intervals are added to an OR-based priority queue (the background queue)
  // The top-n iterators (where n = minimumShouldMatch) are popped from this queue
  // and added to an AND-based priority queue (the proximity queue)
  // Iteration over intervals then proceeds according to the algorithm used by
  // UnorderedIntervalIterator based on intervals in the proximity queue, with
  // the one change that when an iterator is popped from the proximity queue, it
  // is inserted back into the background queue, and replaced by the top iterator
  // from the background queue.
  static class MinimumShouldMatchIntervalIterator extends IntervalIterator {

    private final DocIdSetIterator approximation;
    private final DisiPriorityQueue disiQueue;
    private final PriorityQueue<IntervalIterator> proximityQueue;
    private final PriorityQueue<IntervalIterator> backgroundQueue;
    private final float matchCost;
    private final int minShouldMatch;
    private final Collection<IntervalIterator> currentIterators = new ArrayList<>();
    private final MinimizingConjunctionIntervalsSource.MatchCallback onMatch;

    private int start, end, queueEnd, slop;
    private IntervalIterator lead;

    MinimumShouldMatchIntervalIterator(
        Collection<IntervalIterator> subs,
        int minShouldMatch,
        MinimizingConjunctionIntervalsSource.MatchCallback onMatch) {
      this.disiQueue = new DisiPriorityQueue(subs.size());
      float mc = 0;
      for (IntervalIterator it : subs) {
        this.disiQueue.add(new DisiWrapper(it));
        mc += it.matchCost();
      }
      this.approximation = new DisjunctionDISIApproximation(disiQueue);
      this.matchCost = mc;
      this.minShouldMatch = minShouldMatch;
      this.onMatch = onMatch;

      this.proximityQueue =
          PriorityQueue.usingComparator(
              minShouldMatch,
              Comparator.comparingInt(IntervalIterator::start)
                  .thenComparing(Comparator.comparingInt(IntervalIterator::end).reversed()));
      this.backgroundQueue =
          PriorityQueue.usingComparator(
              subs.size(),
              Comparator.comparingInt(IntervalIterator::end)
                  .thenComparing(Comparator.comparingInt(IntervalIterator::start).reversed()));
    }

    @Override
    public int start() {
      return start;
    }

    @Override
    public int end() {
      return end;
    }

    @Override
    public int gaps() {
      return slop;
    }

    @Override
    public int nextInterval() throws IOException {
      lead = null;
      // first, find a matching interval beyond the current start
      while (this.proximityQueue.size() == minShouldMatch
          && proximityQueue.top().start() == start) {
        IntervalIterator it = proximityQueue.pop();
        if (it != null && it.nextInterval() != IntervalIterator.NO_MORE_INTERVALS) {
          backgroundQueue.add(it);
          IntervalIterator next = backgroundQueue.pop();
          assert next != null; // it's just been added!
          proximityQueue.add(next);
          updateRightExtreme(next);
        }
      }
      if (this.proximityQueue.size() < minShouldMatch)
        return start = end = IntervalIterator.NO_MORE_INTERVALS;
      // then, minimize it
      do {
        onMatch.onMatch();
        start = proximityQueue.top().start();
        end = queueEnd;
        slop = width();
        for (IntervalIterator it : proximityQueue) {
          slop -= it.width();
        }
        if (proximityQueue.top().end() == end) {
          return start;
        }
        lead = proximityQueue.pop();
        if (lead != null) {
          if (lead.nextInterval() != NO_MORE_INTERVALS) {
            backgroundQueue.add(lead);
          }
          IntervalIterator next = backgroundQueue.pop();
          if (next != null) {
            proximityQueue.add(next);
            updateRightExtreme(next);
          }
        }
      } while (this.proximityQueue.size() == minShouldMatch && end == queueEnd);
      return start;
    }

    Collection<IntervalIterator> getCurrentIterators() {
      currentIterators.clear();
      if (lead != null) {
        currentIterators.add(lead);
      }
      for (IntervalIterator it : this.proximityQueue) {
        if (it.end() <= end) {
          currentIterators.add(it);
        }
      }
      return currentIterators;
    }

    private void reset() throws IOException {
      this.proximityQueue.clear();
      this.backgroundQueue.clear();
      // First we populate the background queue
      for (DisiWrapper dw = disiQueue.topList(); dw != null; dw = dw.next) {
        if (dw.intervals.nextInterval() != NO_MORE_INTERVALS) {
          this.backgroundQueue.add(dw.intervals);
        }
      }
      // Then we pop the first minShouldMatch entries and add them to the proximity queue
      this.queueEnd = -1;
      for (int i = 0; i < minShouldMatch; i++) {
        IntervalIterator it = this.backgroundQueue.pop();
        if (it == null) {
          break;
        }
        this.proximityQueue.add(it);
        updateRightExtreme(it);
      }
      start = end = -1;
    }

    private void updateRightExtreme(IntervalIterator it) {
      int itEnd = it.end();
      if (itEnd > queueEnd) {
        queueEnd = itEnd;
      }
    }

    @Override
    public float matchCost() {
      return matchCost;
    }

    @Override
    public int docID() {
      return approximation.docID();
    }

    @Override
    public int nextDoc() throws IOException {
      int doc = approximation.nextDoc();
      reset();
      return doc;
    }

    @Override
    public int advance(int target) throws IOException {
      int doc = approximation.advance(target);
      reset();
      return doc;
    }

    @Override
    public long cost() {
      return approximation.cost();
    }
  }

  static class MinimumMatchesIterator implements IntervalMatchesIterator {

    boolean cached = true;
    final MinimumShouldMatchIntervalIterator iterator;
    final Map<IntervalIterator, CachingMatchesIterator> lookup;

    MinimumMatchesIterator(
        MinimumShouldMatchIntervalIterator iterator,
        Map<IntervalIterator, CachingMatchesIterator> lookup) {
      this.iterator = iterator;
      this.lookup = lookup;
    }

    @Override
    public boolean next() throws IOException {
      if (cached) {
        cached = false;
        return true;
      }
      return iterator.nextInterval() != IntervalIterator.NO_MORE_INTERVALS;
    }

    @Override
    public int startPosition() {
      return iterator.start();
    }

    @Override
    public int endPosition() {
      return iterator.end();
    }

    @Override
    public int startOffset() throws IOException {
      int start = Integer.MAX_VALUE;
      for (IntervalIterator it : iterator.getCurrentIterators()) {
        CachingMatchesIterator cms = lookup.get(it);
        start = Math.min(start, cms.startOffset());
      }
      return start;
    }

    @Override
    public int endOffset() throws IOException {
      int end = 0;
      for (IntervalIterator it : iterator.getCurrentIterators()) {
        CachingMatchesIterator cms = lookup.get(it);
        end = Math.max(end, cms.endOffset());
      }
      return end;
    }

    @Override
    public int gaps() {
      return iterator.gaps();
    }

    @Override
    public int width() {
      return iterator.width();
    }

    @Override
    public MatchesIterator getSubMatches() throws IOException {
      List<MatchesIterator> mis = new ArrayList<>();
      for (IntervalIterator it : iterator.getCurrentIterators()) {
        CachingMatchesIterator cms = lookup.get(it);
        MatchesIterator mi = cms.getSubMatches();
        mis.add(mi == null ? cms : mi);
      }
      return MatchesUtils.disjunction(mis);
    }

    @Override
    public Query getQuery() {
      return null;
    }
  }
}
