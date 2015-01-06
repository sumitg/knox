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
package org.apache.hadoop.gateway.service.definition;

import org.junit.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ServiceDefinitionTest {

  @Test
  public void testUnmarshalling() throws Exception {
    JAXBContext context = JAXBContext.newInstance(ServiceDefinition.class);
    Unmarshaller unmarshaller = context.createUnmarshaller();
    URL url = ClassLoader.getSystemResource("services/yarn-rm/2.5.0/service.xml");
    ServiceDefinition definition = (ServiceDefinition) unmarshaller.unmarshal(url.openStream());
    assertEquals("resourcemanager", definition.getName());
    assertEquals("RESOURCEMANAGER", definition.getRole());
    assertEquals("2.5.0", definition.getVersion());
    List<Route> bindings = definition.getRoutes();
    assertNotNull(bindings);
    assertEquals(12, bindings.size());
    assertNotNull(bindings.get(0).getPath());
    url = ClassLoader.getSystemResource("services/hbase/0.98.0/service.xml");
    definition = (ServiceDefinition) unmarshaller.unmarshal(url.openStream());
    assertNotNull(definition.getDispatch());
    assertEquals("org.apache.hadoop.gateway.hbase.HBaseHttpClientDispatch", definition.getDispatch().getClassName());
    url = ClassLoader.getSystemResource("services/webhdfs/2.4.0/service.xml");
    definition = (ServiceDefinition) unmarshaller.unmarshal(url.openStream());
    assertNotNull(definition.getDispatch());
    assertEquals("org.apache.hadoop.gateway.hdfs.dispatch.HdfsDispatch", definition.getDispatch().getClassName());
    assertEquals("org.apache.hadoop.gateway.hdfs.dispatch.WebHdfsHaHttpClientDispatch", definition.getDispatch().getHaClassName());
  }
}
