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

package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.events.PreEventContext;

/**
 * This abstract class needs to be extended to  provide implementation of actions that needs
 * to be performed before a particular event occurs on a metastore. These methods
 * are called before an event occurs on metastore.
 * 需要继承这个抽象类以提供在特定事件发生在metastore之前需要执行的动作的实现。 在事件发生在metastore之前调用这些方法。
 */

public abstract class MetaStorePreEventListener implements Configurable {

  private Configuration conf;

  public MetaStorePreEventListener(Configuration config){
    this.conf = config;
  }

  public abstract void onEvent(PreEventContext context)
      throws MetaException, NoSuchObjectException, InvalidOperationException;

  @Override
  public Configuration getConf() {
    return this.conf;
  }

  @Override
  public void setConf(Configuration config) {
    this.conf = config;
  }
}
