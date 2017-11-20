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


import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.interceptor.InvocationContext;
import java.util.concurrent.locks.Lock;

@Dependent
public class DefaultLockedStrategy implements LockedStrategy {
    @Inject
    private LockSupplierStorage lockSupplierStorage;

    @Override
    public Object execute(InvocationContext ic) throws Exception {
        final Lock lock = lockSupplierStorage.getLockSupplier(ic).get();
        try {
            return ic.proceed();
        } finally {
            lock.unlock();
        }
    }
}
