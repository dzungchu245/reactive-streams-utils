/******************************************************************************
 * Licensed under Public Domain (CC0)                                         *
 *                                                                            *
 * To the extent possible under law, the person who associated CC0 with       *
 * this code has waived all copyright and related or neighboring              *
 * rights to this code.                                                       *
 *                                                                            *
 * You should have received a copy of the CC0 legalcode along with this       *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.     *
 ******************************************************************************/

package org.reactivestreams.utils;

import org.reactivestreams.utils.spi.Stage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.*;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * A builder for a {@link Processor}.
 *
 * @param <T> The type of the elements that the processor consumes.
 * @param <R> The type of the elements that the processor emits.
 * @see ReactiveStreams
 */
public final class ProcessorBuilder<T, R> extends ReactiveStreamsBuilder<Processor<T, R>> {

  ProcessorBuilder(Stage stage, ReactiveStreamsBuilder<?> previous) {
    super(stage, previous);
  }

  /**
   * Map the elements emitted by this processor using the <code>mapper</code> function.
   *
   * @param mapper The function to use to map the elements.
   * @param <S>    The type of elements that the <code>mapper</code> function emits.
   * @return A new processor builder that consumes elements of type <code>T</code> and emits the mapped elements.
   */
  public <S> ProcessorBuilder<T, S> map(Function<? super R, ? extends S> mapper) {
    return new ProcessorBuilder<>(new Stage.Map(mapper), this);
  }

  /**
   * Filter elements emitted by this processor using the given {@link Predicate}.
   * <p>
   * Any elements that return <code>true</code> when passed to the {@link Predicate} will be emitted, all other
   * elements will be dropped.
   *
   * @param predicate The predicate to apply to each element.
   * @return A new processor builder.
   */
  public ProcessorBuilder<T, R> filter(Predicate<? super R> predicate) {
    return new ProcessorBuilder<>(new Stage.Filter(() -> predicate), this);
  }

  /**
   * Map the elements to publishers, and flatten so that the elements emitted by publishers produced by the
   * {@code mapper} function are emitted from this stream.
   * <p>
   * This method operates on one publisher at a time. The result is a concatenation of elements emitted from all the
   * publishers produced by the mapper function.
   *
   * @param mapper The mapper function.
   * @param <S>    The type of the elements emitted from the new processor.
   * @return A new processor builder.
   */
  public <S> ProcessorBuilder<T, S> flatMap(Function<? super R, PublisherBuilder<? extends S>> mapper) {
    return new ProcessorBuilder<>(new Stage.FlatMap(mapper.andThen(PublisherBuilder::toGraph)), this);
  }

  /**
   * Map the elements to {@link CompletionStage}, and flatten so that the elements the values redeemed by each
   * {@link CompletionStage} are emitted from this processor.
   * <p>
   * This method only works with one element at a time. When an element is received, the {@code mapper} function is
   * executed, and the next element is not consumed or passed to the {@code mapper} function until the previous
   * {@link CompletionStage} is redeemed. Hence this method also guarantees that ordering of the stream is maintained.
   *
   * @param mapper The mapper function.
   * @param <S>    The type of the elements emitted from the new processor.
   * @return A new processor builder.
   */
  public <S> ProcessorBuilder<T, S> flatMapCompletionStage(Function<? super R, ? extends CompletionStage<? extends S>> mapper) {
    return new ProcessorBuilder<>(new Stage.FlatMapCompletionStage((Function) mapper), this);
  }

  /**
   * Map the elements to {@link Iterable}'s, and flatten so that the elements contained in each iterable are
   * emitted by this stream.
   * <p>
   * This method operates on one iterable at a time. The result is a concatenation of elements contain in all the
   * iterables returned by the {@code mapper} function.
   *
   * @param mapper The mapper function.
   * @param <S>    The type of the elements emitted from the new processor.
   * @return A new processor builder.
   */
  public <S> ProcessorBuilder<T, S> flatMapIterable(Function<? super R, ? extends Iterable<? extends S>> mapper) {
    return new ProcessorBuilder<>(new Stage.FlatMapIterable((Function) mapper), this);
  }

  /**
   * Truncate this stream, ensuring the stream is no longer than {@code maxSize} elements in length.
   * <p>
   * If {@code maxSize} is reached, the stream will be completed, and upstream will be cancelled. Completion of the
   * stream will occur immediately when the element that satisfies the {@code maxSize} is received.
   *
   * @param maxSize The maximum size of the returned stream.
   * @return A new processor builder.
   */
  public ProcessorBuilder<T, R> limit(long maxSize) {
    if (maxSize < 0) {
      throw new IllegalArgumentException("Cannot limit a stream to less than zero elements.");
    } else if (maxSize == 0) {
      // todo this is perhaps not the desired behaviour - it means an element must be received before the stream will
      // be completed. but then again, limiting a stream to have zero size is a strange thing to do, as running the
      // stream in theory will then achieve nothing, so this edge case behavior probably isn't important to worry too
      // much about.
      return takeWhile(e -> false);
    } else {
      return new ProcessorBuilder<>(new Stage.TakeWhile(() -> new Predicates.LimitPredicate<>(maxSize), true), this);
    }
  }

  /**
   * Discard the first {@code n} of this stream. If this stream contains fewer than {@code n} elements, this stream will
   * effectively be an empty stream.
   *
   * @param n The number of elements to discard.
   * @return A new processor builder.
   */
  public ProcessorBuilder<T, R> skip(long n) {
    return new ProcessorBuilder<>(new Stage.Filter(() -> new Predicates.SkipPredicate<>(n)), this);
  }

  /**
   * Take the longest prefix of elements from this stream that satisfy the given {@code predicate}.
   * <p>
   * When the {@code predicate} returns false, the stream will be completed, and upstream will be cancelled.
   *
   * @param predicate The predicate.
   * @return A new publisher builder.
   */
  public ProcessorBuilder<T, R> takeWhile(Predicate<? super R> predicate) {
    return new ProcessorBuilder<>(new Stage.TakeWhile(() -> predicate, false), this);
  }

  /**
   * Drop the longest prefix of elements from this stream that satisfy the given {@code predicate}.
   * <p>
   * As long as the {@code predicate} returns true, no elements will be emitted from this stream. Once the first element
   * is encountered for which the {@code predicate} returns false, all subsequent elements will be emitted, and the
   * {@code predicate} will no longer be invoked.
   *
   * @param predicate The predicate.
   * @return A new processor builder.
   */
  public ProcessorBuilder<T, R> dropWhile(Predicate<? super R> predicate) {
    return new ProcessorBuilder<>(new Stage.Filter(() -> new Predicates.DropWhilePredicate<>(predicate)), this);
  }

  /**
   * Perform a reduction on the elements of this stream, using the provided identity value and the accumulation
   * function.
   * <p>
   * The result of the reduction is emitted from the returned processor.
   *
   * @param identity The identity value.
   * @param accumulator The accumulator function.
   * @return A new processor builder.
   */
  public ProcessorBuilder<T, R> reduce(R identity, BinaryOperator<R> accumulator) {
    return collect(Reductions.reduce(identity, accumulator));
  }

  /**
   * Perform a reduction on the elements of this stream, using the provided identity value, accumulation function and
   * combiner function.
   * <p>
   * The result of the reduction is emitted from the returned processor.
   *
   * @param identity    The identity value.
   * @param accumulator The accumulator function.
   * @param combiner    The combiner function.
   * @return A new processor builder.
   */
  public <S> ProcessorBuilder<T, S> reduce(S identity,
      BiFunction<S, ? super R, S> accumulator,
      BinaryOperator<S> combiner) {

    return collect(Reductions.reduce(identity, accumulator, combiner));
  }

  /**
   * Collect the elements emitted by this processor builder using the given {@link Collector}.
   * <p>
   * Since Reactive Streams are intrinsically sequential, only the accumulator of the collector will be used, the
   * combiner will not be used.
   * <p>
   * The result of the reduction is emitted from the returned processor.
   *
   * @param collector The collector to collect the elements.
   * @param <S>       The result of the collector.
   * @param <A>       The accumulator type.
   * @return A new processor builder.
   */
  public <S, A> ProcessorBuilder<T, S> collect(Collector<? super R, A, S> collector) {
    return new ProcessorBuilder<>(new Stage.CollectProcessor(collector), this);
  }

  /**
   * Collect the elements emitted by this processor builder into a {@link List}
   *
   * @return A {@link SubscriberBuilder} that represents this processor builders inlet.
   */
  public SubscriberBuilder<T, List<R>> toList() {
    return to(ReactiveStreams.toList());
  }

  /**
   * Find the first element emitted by the {@link Processor}, and return it in a
   * {@link java.util.concurrent.CompletionStage}.
   * <p>
   * If the stream is completed before a single element is emitted, then {@link Optional#empty()} will be emitted.
   *
   * @return A {@link SubscriberBuilder} that represents this processor builders inlet.
   */
  public SubscriberBuilder<T, Optional<R>> findFirst() {
    return new SubscriberBuilder<>(Stage.FindFirst.INSTANCE, this);
  }

  /**
   * Connect the outlet of the {@link Processor} built by this builder to the given {@link Subscriber}.
   *
   * @param subscriber The subscriber to connect.
   * @return A {@link SubscriberBuilder} that represents this processor builders inlet.
   */
  public SubscriberBuilder<T, Void> to(Subscriber<R> subscriber) {
    return new SubscriberBuilder<>(new Stage.Subscriber(subscriber), this);
  }

  /**
   * Connect the outlet of this processor builder to the given {@link SubscriberBuilder} graph.
   *
   * @param subscriber The subscriber builder to connect.
   * @return A {@link SubscriberBuilder} that represents this processor builders inlet.
   */
  public <S> SubscriberBuilder<T, S> to(SubscriberBuilder<R, S> subscriber) {
    return new SubscriberBuilder<>(new InternalStages.Nested(subscriber), this);
  }

  /**
   * Connect the outlet of the {@link Processor} built by this builder to the given {@link Processor}.
   *
   * @param processor The processor to connect.
   * @return A {@link ProcessorBuilder} that represents this processor builders inlet, and the passed in processors
   * outlet.
   */
  public <S> ProcessorBuilder<T, S> via(ProcessorBuilder<R, S> processor) {
    return new ProcessorBuilder<>(new InternalStages.Nested(processor), this);
  }

  /**
   * Connect the outlet of this processor builder to the given {@link ProcessorBuilder} graph.
   *
   * @param processor The processor builder to connect.
   * @return A {@link ProcessorBuilder} that represents this processor builders inlet, and the passed in
   * processor builders outlet.
   */
  public <S> ProcessorBuilder<T, S> via(Processor<R, S> processor) {
    return new ProcessorBuilder<>(new Stage.Processor(processor), this);
  }

  @Override
  public Processor<T, R> build(ReactiveStreamsEngine engine) {
    return engine.buildProcessor(toGraph(true, true));
  }
}
