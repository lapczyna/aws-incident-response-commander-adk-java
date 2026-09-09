package com.lapczynski.commander.adk.plugin;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableSubscriber;
import java.util.Map;
import java.util.concurrent.Callable;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;

/**
 * Carries correlation context across RxJava's scheduler boundaries.
 *
 * <p>MDC is thread-local. ADK returns {@code Flowable<Event>} and runs work on pooled schedulers,
 * so a value put into the MDC on the caller's thread is simply absent on the thread that emits
 * events. The symptom is not an error: it is log lines with an empty {@code incidentId} for exactly
 * the operations worth correlating, appearing only under concurrency, and often disappearing when
 * someone adds logging to investigate.
 *
 * <p>Worse than absence is <em>leakage</em>. Pooled threads are reused, so a value left behind by
 * one invocation attaches itself to whatever runs next on that thread. A log line then carries a
 * confidently wrong incident id, which is more damaging than a blank one: a blank field is noticed,
 * and a wrong one is trusted. Every path here restores the previous context afterwards rather than
 * clearing it, because on a pooled thread the previous context belongs to someone else.
 *
 * <p><strong>The context applies downstream, not upstream.</strong> The wrapper installs the
 * captured context around each signal it passes on, so operators placed <em>after</em> {@link
 * #withContext} see it and operators placed before it do not. That is the useful direction: the
 * interesting code is the consumer reading events out of a runner, and a first attempt that wrapped
 * the whole stream with {@code Flowable.using} silently did nothing, because the resource is
 * created on the subscribing thread while the work happens on a scheduler worker.
 *
 * <p>Deliberately explicit rather than a global {@code RxJavaPlugins} assembly hook. A global hook
 * would cover every stream in the process, including ADK's internals and anything a future
 * dependency schedules, and its cost and failure modes would be invisible at the call sites relying
 * on it.
 */
public final class MdcPropagation {

  private MdcPropagation() {}

  /**
   * Wraps a stream so that everything downstream of this call sees the context captured here.
   *
   * <p>Captured at assembly time, on the thread that knows which incident this is, and re-installed
   * around every {@code onNext}, {@code onError} and {@code onComplete} delivered downstream.
   */
  public static <T> Flowable<T> withContext(Flowable<T> source) {
    Map<String, String> captured = MDC.getCopyOfContextMap();
    return source.lift(downstream -> new ContextSubscriber<>(downstream, captured));
  }

  /** Runs a callable with the given context, restoring whatever was there before. */
  public static <T> T call(Map<String, String> context, Callable<T> work) throws Exception {
    try (Scope ignored = new Scope(context)) {
      return work.call();
    }
  }

  /** Runs an action with the given context, restoring whatever was there before. */
  public static void run(Map<String, String> context, Runnable work) {
    try (Scope ignored = new Scope(context)) {
      work.run();
    }
  }

  /** Installs the captured context around each signal passed downstream. */
  private static final class ContextSubscriber<T> implements FlowableSubscriber<T>, Subscription {

    private final org.reactivestreams.Subscriber<? super T> downstream;
    private final Map<String, String> context;
    private Subscription upstream;

    ContextSubscriber(
        org.reactivestreams.Subscriber<? super T> downstream, Map<String, String> context) {
      this.downstream = downstream;
      this.context = context;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.upstream = subscription;
      try (Scope ignored = new Scope(context)) {
        downstream.onSubscribe(this);
      }
    }

    @Override
    public void onNext(T item) {
      try (Scope ignored = new Scope(context)) {
        downstream.onNext(item);
      }
    }

    @Override
    public void onError(Throwable error) {
      try (Scope ignored = new Scope(context)) {
        downstream.onError(error);
      }
    }

    @Override
    public void onComplete() {
      try (Scope ignored = new Scope(context)) {
        downstream.onComplete();
      }
    }

    @Override
    public void request(long n) {
      upstream.request(n);
    }

    @Override
    public void cancel() {
      upstream.cancel();
    }
  }

  /**
   * Installs a context and restores the previous one on close.
   *
   * <p>Restores rather than clears, for the reason in the class comment.
   */
  private static final class Scope implements AutoCloseable {

    private final Map<String, String> previous;

    Scope(Map<String, String> context) {
      this.previous = MDC.getCopyOfContextMap();
      if (context == null || context.isEmpty()) {
        MDC.clear();
      } else {
        MDC.setContextMap(context);
      }
    }

    @Override
    public void close() {
      if (previous == null || previous.isEmpty()) {
        MDC.clear();
      } else {
        MDC.setContextMap(previous);
      }
    }
  }
}
