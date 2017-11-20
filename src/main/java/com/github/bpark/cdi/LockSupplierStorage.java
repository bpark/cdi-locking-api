/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.bpark.cdi;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Typed;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.github.bpark.cdi.LockType.READ;

@ApplicationScoped
@Typed(LockSupplierStorage.class)
public class LockSupplierStorage implements LockFactory {
    private final ConcurrentMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    // read or write
    private final ConcurrentMap<Method, LockSupplier> lockSuppliers = new ConcurrentHashMap<>();

    @Inject
    private BeanManager beanManager;

    protected LockSupplier getLockSupplier(final InvocationContext ic) {
        final Method key = ic.getMethod();
        LockSupplier operation = lockSuppliers.get(key);
        if (operation == null) {
            final Class declaringClass = key.getDeclaringClass();
            final AnnotatedType<Object> annotatedType = beanManager.createAnnotatedType(declaringClass);
            final AnnotatedMethod<?> annotatedMethod = findAnnotatedMethod(annotatedType, key);

            Locked config = annotatedMethod.getAnnotation(Locked.class);
            if (config == null) {
                config = annotatedType.getAnnotation(Locked.class);
            }
            final LockFactory factory = config.factory() != LockFactory.class ?
                    LockFactory.class.cast(
                            beanManager.getReference(beanManager.resolve(
                                    beanManager.getBeans(
                                            config.factory())),
                                    LockFactory.class, null)) : this;

            final ReadWriteLock writeLock = factory.newLock(annotatedMethod, config.fair());
            final long timeout = config.timeoutUnit().toMillis(config.timeout());
            final Lock lock = config.operation() == READ ? writeLock.readLock() : writeLock.writeLock();

            if (timeout > 0) {
                operation = () -> {
                    try {
                        if (!lock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException("Can't lock for " + key + " in " + timeout + "ms");
                        }
                    } catch (final InterruptedException e) {
                        Thread.interrupted();
                        throw new IllegalStateException("Locking interrupted", e);
                    }
                    return lock;
                };
            } else {
                operation = () -> {
                    lock.lock();
                    return lock;
                };
            }

            final LockSupplier existing = lockSuppliers.putIfAbsent(key, operation);
            if (existing != null) {
                operation = existing;
            }
        }
        return operation;
    }

    @Override
    public ReadWriteLock newLock(final AnnotatedMethod<?> method, final boolean fair) {
        final String name = method.getJavaMember().getDeclaringClass().getName();
        ReadWriteLock lock = locks.get(name);
        if (lock == null) {
            lock = new ReentrantReadWriteLock(fair);
            final ReadWriteLock existing = locks.putIfAbsent(name, lock);
            if (existing != null) {
                lock = existing;
            }
        }
        return lock;
    }

    private AnnotatedMethod<?> findAnnotatedMethod(final AnnotatedType<?> type, final Method method) {
        AnnotatedMethod<?> annotatedMethod = null;
        for (final AnnotatedMethod<?> am : type.getMethods()) {
            if (am.getJavaMember().equals(method)) {
                annotatedMethod = am;
                break;
            }
        }
        if (annotatedMethod == null) {
            throw new IllegalStateException("No annotated method for " + method);
        }
        return annotatedMethod;
    }
}
