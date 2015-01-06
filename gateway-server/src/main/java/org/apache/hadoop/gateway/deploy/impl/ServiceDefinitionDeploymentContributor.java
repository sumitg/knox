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
package org.apache.hadoop.gateway.deploy.impl;

import org.apache.hadoop.gateway.deploy.DeploymentContext;
import org.apache.hadoop.gateway.deploy.ServiceDeploymentContributorBase;
import org.apache.hadoop.gateway.descriptor.FilterParamDescriptor;
import org.apache.hadoop.gateway.descriptor.ResourceDescriptor;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteRulesDescriptor;
import org.apache.hadoop.gateway.service.definition.RewriteFilter;
import org.apache.hadoop.gateway.service.definition.ServiceDefinition;
import org.apache.hadoop.gateway.service.definition.UrlBinding;
import org.apache.hadoop.gateway.topology.Service;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceDefinitionDeploymentContributor extends ServiceDeploymentContributorBase {

    private ServiceDefinition serviceDefinition;

    private UrlRewriteRulesDescriptor serviceRules;

    public ServiceDefinitionDeploymentContributor(ServiceDefinition serviceDefinition, UrlRewriteRulesDescriptor serviceRules) {
        this.serviceDefinition = serviceDefinition;
        this.serviceRules = serviceRules;
    }

    @Override
    public String getRole() {
        return serviceDefinition.getRole();
    }

    @Override
    public String getName() {
        return serviceDefinition.getName();
    }

    @Override
    public void contributeService(DeploymentContext context, Service service) throws Exception {
        System.out.println("contributing service def");
        contributeRewriteRules(context, service);
        contributeResources(context, service);
    }

    private void contributeRewriteRules(DeploymentContext context, Service service) {
        if (serviceRules != null) {
            UrlRewriteRulesDescriptor clusterRules = context.getDescriptor("rewrite");
            clusterRules.addRules(serviceRules);
        }
    }

    private void contributeResources(DeploymentContext context, Service service) {
        Map<String, String> filterParams = new HashMap<String, String>();
        List<UrlBinding> bindings = serviceDefinition.getUrlBindings();
        for (UrlBinding binding : bindings) {
            List<RewriteFilter> filters = binding.getRewriteFilters();
            if (filters != null && !filters.isEmpty()) {
                filterParams.clear();
                for (RewriteFilter filter : filters) {
                    filterParams.put(filter.getApplyTo(), filter.getRef());
                }
            }
            try {
                contributeResource(context, service, binding.getPattern(), filterParams);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }

    }

    private void contributeResource(DeploymentContext context, Service service, String pattern, Map<String, String> filterParams) throws URISyntaxException {
        List<FilterParamDescriptor> params = new ArrayList<FilterParamDescriptor>();
        ResourceDescriptor resource = context.getGatewayDescriptor().addResource();
        resource.role(service.getRole());
        resource.pattern(pattern);
        addWebAppSecFilters(context, service, resource);
        addAuthenticationFilter(context, service, resource);
        addIdentityAssertionFilter(context, service, resource);
        addAuthorizationFilter(context, service, resource);
        if (!filterParams.isEmpty()) {
            for (Map.Entry<String, String> filterParam : filterParams.entrySet()) {
                params.add(resource.createFilterParam().name(filterParam.getKey()).value(filterParam.getValue()));
            }
        }
        addRewriteFilter( context, service, resource, params );
        addDispatchFilter( context, service, resource, "dispatch", "http-client" );
    }
}
