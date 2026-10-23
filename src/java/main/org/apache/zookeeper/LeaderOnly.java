/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记注解：被标注的类型 / 方法 / 字段只在 Leader 角色下被创建或使用，
 * Follower / Observer 的请求处理链中不会出现。
 * <p>
 * 例如 Leader 的处理链：
 * <pre>
 * PrepRequestProcessor -&gt; ProposalRequestProcessor -&gt; CommitProcessor
 *     -&gt; Leader.ToBeAppliedRequestProcessor -&gt; FinalRequestProcessor
 * </pre>
 * 其中 {@code ProposalRequestProcessor}（及其内部的 {@code AckRequestProcessor}）
 * 仅由 {@code LeaderZooKeeperServer#setupRequestProcessors()} 构建。
 * <p>
 * 与 {@code @GuardedBy} 类似，仅用于阅读源码时的文档说明，不影响运行时行为。
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface LeaderOnly {

    /**
     * 可选说明，例如为什么只属于 Leader。
     */
    String value() default "";
}
