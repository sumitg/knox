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
package org.apache.hadoop.gateway.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.hadoop.gateway.deploy.ServiceDeploymentContributor;
import org.apache.hadoop.gateway.deploy.impl.ServiceDefinitionDeploymentContributor;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteRulesDescriptor;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteRulesDescriptorFactory;
import org.apache.hadoop.gateway.service.definition.ServiceDefinition;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class ServiceDefinitionsLoader {

    public static Set<ServiceDeploymentContributor> loadServiceDefinitions(File servicesDir) {
        Set<ServiceDeploymentContributor> contributors = new HashSet<ServiceDeploymentContributor>();
        if (servicesDir.exists() && servicesDir.isDirectory()) {
            JAXBContext context = null;
            try {
                context = JAXBContext.newInstance(ServiceDefinition.class);
                Unmarshaller unmarshaller = context.createUnmarshaller();
                Collection<File> files = FileUtils.listFiles(servicesDir, new IOFileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return file.getName().contains("service");
                    }

                    @Override
                    public boolean accept(File dir, String name) {
                        return name.contains("service");
                    }
                }, TrueFileFilter.INSTANCE);
                for (File file : files) {
                    try {
                        FileInputStream inputStream = new FileInputStream(file);
                        ServiceDefinition definition = (ServiceDefinition) unmarshaller.unmarshal(inputStream);
                        //look for rewrite rules as a sibling (for now)
                        UrlRewriteRulesDescriptor rewriteRulesDescriptor = loadRewriteRules(file.getParentFile());
                        contributors.add(new ServiceDefinitionDeploymentContributor(definition, rewriteRulesDescriptor));
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            } catch (JAXBException e) {
                e.printStackTrace();
            }
        }
        return contributors;
    }

    private static UrlRewriteRulesDescriptor loadRewriteRules(File servicesDir) {
        File rewriteFile = new File(servicesDir, "rewrite.xml");
        if (rewriteFile.exists()) {
            InputStream stream = null;
            try {
                stream = new FileInputStream(rewriteFile);
                Reader reader = new InputStreamReader(stream);
                UrlRewriteRulesDescriptor rules = UrlRewriteRulesDescriptorFactory.load(
                        "xml", reader);
                reader.close();
                stream.close();
                return rules;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
