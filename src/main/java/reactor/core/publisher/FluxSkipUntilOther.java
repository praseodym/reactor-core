/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Skips values from the main publisher until the other publisher signals
 * an onNext or onComplete.
 *
 * @param <T> the value type of the main Publisher
 * @param <U> the value type of the other Publisher
 */

/**
 * @see <a href="https://github.com/reactor/reactive-streams-commons">https://github.com/reactor/reactive-streams-commons</a>
 */
final class FluxSkipUntilOther<T, U> extends FluxSource<T, T> {

	final Publisher<U> other;

	public FluxSkipUntilOther(Publisher<? extends T> source, Publisher<U> other) {
		super(source);
		this.other = Objects.requireNonNull(other, "other");
	}

	@Override
	public long getPrefetch() {
		return Long.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		SkipUntilMainSubscriber<T> mainSubscriber = new SkipUntilMainSubscriber<>(s);

		SkipUntilOtherSubscriber<U> otherSubscriber = new SkipUntilOtherSubscriber<>(mainSubscriber);

		other.subscribe(otherSubscriber);

		source.subscribe(mainSubscriber);
	}

	static final class SkipUntilOtherSubscriber<U> implements Subscriber<U> {
		final SkipUntilMainSubscriber<?> main;

		public SkipUntilOtherSubscriber(SkipUntilMainSubscriber<?> main) {
			this.main = main;
		}

		@Override
		public void onSubscribe(Subscription s) {
			main.setOther(s);

			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(U t) {
			if (main.gate) {
				return;
			}
			SkipUntilMainSubscriber<?> m = main;
			m.other.cancel();
			m.gate = true;
			m.other = Operators.cancelledSubscription();
		}

		@Override
		public void onError(Throwable t) {
			SkipUntilMainSubscriber<?> m = main;
			if (m.gate) {
				return;
			}
			m.onError(t);
		}

		@Override
		public void onComplete() {
			SkipUntilMainSubscriber<?> m = main;
			if (m.gate) {
				return;
			}
			m.gate = true;
			m.other = Operators.cancelledSubscription();
		}


	}

	static final class SkipUntilMainSubscriber<T>
	  implements Subscriber<T>, Subscription {

		final Subscriber<T> actual;

		volatile Subscription main;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SkipUntilMainSubscriber, Subscription> MAIN =
		  AtomicReferenceFieldUpdater.newUpdater(SkipUntilMainSubscriber.class, Subscription.class, "main");

		volatile Subscription other;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SkipUntilMainSubscriber, Subscription> OTHER =
		  AtomicReferenceFieldUpdater.newUpdater(SkipUntilMainSubscriber.class, Subscription.class, "other");

		volatile boolean gate;

		public SkipUntilMainSubscriber(Subscriber<? super T> actual) {
			this.actual = Operators.serialize(actual);
		}

		void setOther(Subscription s) {
			if (!OTHER.compareAndSet(this, null, s)) {
				s.cancel();
				if (other != Operators.cancelledSubscription()) {
					Operators.reportSubscriptionSet();
				}
			}
		}

		@Override
		public void request(long n) {
			main.request(n);
		}

		void cancelMain() {
			Subscription s = main;
			if (s != Operators.cancelledSubscription()) {
				s = MAIN.getAndSet(this, Operators.cancelledSubscription());
				if (s != null && s != Operators.cancelledSubscription()) {
					s.cancel();
				}
			}
		}

		void cancelOther() {
			Subscription s = other;
			if (s != Operators.cancelledSubscription()) {
				s = OTHER.getAndSet(this, Operators.cancelledSubscription());
				if (s != null && s != Operators.cancelledSubscription()) {
					s.cancel();
				}
			}
		}

		@Override
		public void cancel() {
			cancelMain();
			cancelOther();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (!MAIN.compareAndSet(this, null, s)) {
				s.cancel();
				if (main != Operators.cancelledSubscription()) {
					Operators.reportSubscriptionSet();
				}
			} else {
				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (gate) {
				actual.onNext(t);
			} else {
				main.request(1);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (main == null) {
				if (MAIN.compareAndSet(this, null, Operators.cancelledSubscription())) {
					Operators.error(actual, t);
					return;
				}
			}
			cancel();

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			cancelOther();

			actual.onComplete();
		}
	}
}
