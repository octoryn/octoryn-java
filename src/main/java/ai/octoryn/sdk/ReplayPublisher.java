package ai.octoryn.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

final class ReplayPublisher<T> implements Flow.Publisher<T> {
    private final List<T> items = new ArrayList<>();
    private final List<ReplaySubscription> subscriptions = new ArrayList<>();
    private boolean complete;
    private Throwable error;

    @Override
    public synchronized void subscribe(Flow.Subscriber<? super T> subscriber) {
        if (subscriber == null) throw new NullPointerException("subscriber");
        var subscription = new ReplaySubscription(subscriber);
        subscriptions.add(subscription);
        subscriber.onSubscribe(subscription);
    }

    synchronized void publish(T item) {
        if (complete) return;
        items.add(item);
        for (var subscription : List.copyOf(subscriptions)) subscription.drain();
    }

    synchronized void complete() {
        complete = true;
        for (var subscription : List.copyOf(subscriptions)) subscription.drain();
    }

    synchronized void fail(Throwable failure) {
        error = failure;
        complete = true;
        for (var subscription : List.copyOf(subscriptions)) subscription.drain();
    }

    private final class ReplaySubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private int index;
        private long demand;
        private boolean cancelled;
        private boolean terminated;

        private ReplaySubscription(Flow.Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public synchronized void request(long amount) {
            if (amount <= 0) {
                cancelled = true;
                subscriber.onError(new IllegalArgumentException("Demand must be positive."));
                return;
            }
            demand = demand > Long.MAX_VALUE - amount ? Long.MAX_VALUE : demand + amount;
            drain();
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
        }

        private synchronized void drain() {
            if (cancelled || terminated) return;
            while (demand > 0 && index < items.size()) {
                var item = items.get(index++);
                demand--;
                subscriber.onNext(item);
            }
            if (complete && index == items.size()) {
                terminated = true;
                if (error == null) subscriber.onComplete();
                else subscriber.onError(error);
            }
        }
    }
}
