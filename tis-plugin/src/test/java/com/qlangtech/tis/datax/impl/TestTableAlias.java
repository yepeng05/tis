/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qlangtech.tis.datax.impl;

import com.qlangtech.tis.datax.IDataxProcessor;
import junit.framework.TestCase;

/**
 * @author: 百岁（baisui@qlangtech.com）
 * @create: 2022-01-13 14:24
 **/
public class TestTableAlias extends TestCase {

    public void testTableAlias() {
        String tableName = "instancedetail";
        String dbName = "order";

        IDataxProcessor.TableAlias tableAlias = new IDataxProcessor.TableAlias(tableName);
        assertEquals(tableName, tableAlias.getFrom());
        assertEquals(tableName, tableAlias.getTo());

        tableAlias = new IDataxProcessor.TableAlias(dbName + "." + tableName);

        assertEquals(dbName + "." + tableName, tableAlias.getFrom());
        assertEquals(tableName, tableAlias.getTo());
    }
}
