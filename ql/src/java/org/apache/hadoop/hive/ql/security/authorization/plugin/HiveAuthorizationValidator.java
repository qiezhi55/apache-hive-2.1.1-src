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
package org.apache.hadoop.hive.ql.security.authorization.plugin;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.hive.ql.parse.SemanticException;

import java.util.List;

/**
 * Interface used to check if user has privileges to perform certain action.
 * Methods here have corresponding methods in HiveAuthorizer, check method documentation there.
 */
@Private
public interface HiveAuthorizationValidator {

  /**
   * see HiveAuthorizer.checkPrivileges
   */
  void checkPrivileges(HiveOperationType hiveOpType, List<HivePrivilegeObject> inputHObjs,
      List<HivePrivilegeObject> outputHObjs, HiveAuthzContext context) throws HiveAuthzPluginException, HiveAccessControlException;

  /**
   * see HiveAuthorizer.filterListCmdObjects
   */
  List<HivePrivilegeObject> filterListCmdObjects(List<HivePrivilegeObject> listObjs,
      HiveAuthzContext context);

  public List<HivePrivilegeObject> applyRowFilterAndColumnMasking(HiveAuthzContext context,
      List<HivePrivilegeObject> privObjs) throws SemanticException;

  public boolean needTransform();

}
