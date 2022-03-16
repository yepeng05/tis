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

package com.qlangtech.tis.datax;

import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.util.container.CoreConstant;
import com.qlangtech.tis.manage.common.Config;

/**
 * @author: 百岁（baisui@qlangtech.com）
 * @create: 2021-09-03 16:34
 **/
public class TestDataXExecutorWithMysql2Elastic extends BasicDataXExecutorTestCase {

    private boolean hasExecuteStartEngine;
    final Integer jobId = 123;
    final String jobName = "instancedetail_0.json";
    final String dataxName = "mysql_elastic";

    @Override
    public void setUp() throws Exception {
        super.setUp();
        this.hasExecuteStartEngine = false;
    }

    /**
     * 测试配置文件和plugin是否正确下载
     */
    public void testResourceSync() throws Exception {
        final String execTimeStamp = "20220316121256";
        this.executor.exec(jobId, jobName, dataxName, execTimeStamp);
        assertTrue("hasExecuteStartEngine", hasExecuteStartEngine);
    }

    @Override
    protected void setDataDir() {
        Config.setTestDataDir();

        // Config.setDataDir("/tmp/tis");
    }

    @Override
    protected boolean isNotFetchFromCenterRepository() {
        return false;
    }

    protected DataxExecutor createExecutor() {
        return new DataxExecutor(statusRpc, DataXJobSubmit.InstanceType.LOCAL, 300) {
            @Override
            protected void startEngine(Configuration configuration, Integer jobId, String jobName) {
                //  make skip the ex

                int jobSleepIntervalInMillSec = configuration.getInt(
                        CoreConstant.DATAX_CORE_CONTAINER_JOB_SLEEPINTERVAL, 10000);
                assertEquals(3000, jobSleepIntervalInMillSec);
                hasExecuteStartEngine = true;
            }
        };
    }

}
