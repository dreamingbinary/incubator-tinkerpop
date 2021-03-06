/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.lambda;

import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.LambdaHolder;

import java.util.function.Function;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class MapTraversal<S, E> extends AbstractLambdaTraversal<S, E> implements LambdaHolder {

    private E e;
    private final Function<S, E> function;

    public MapTraversal(final Function<S, E> function) {
        this.function = function;
    }

    @Override
    public E next() {
        return this.e;
    }

    @Override
    public void addStart(final Traverser<S> start) {
        this.e = this.function.apply(start.get());
    }

    @Override
    public String toString() {
        return this.function.toString();
    }
}
