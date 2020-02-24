/*
 * Copyright 2020 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.messaging.retry;

/**
 * Template interface for policies that can retry upon an unexpected response.
 *
 * @param <P> Policy type.
 * @param <T> Response type.
 */
public interface RetryResponseSupport<T, P extends RetryResponseSupport<T, P>> {
    /**
     * Registers a predicate to control if the operation should to be repeated upon the response.
     *
     * @param predicate Predicate.
     *
     * @return This instance.
     */
    P whileResponse(RetryResponsePredicate<T> predicate);
}
