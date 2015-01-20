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
import org.apache.hadoop.gateway.descriptor.FilterDescriptor;
import org.apache.hadoop.gateway.descriptor.FilterParamDescriptor;
import org.apache.hadoop.gateway.descriptor.ResourceDescriptor;
import org.apache.hadoop.gateway.dispatch.GatewayDispatchFilter;
import org.apache.hadoop.gateway.filter.rewrite.api.UrlRewriteRulesDescriptor;
import org.apache.hadoop.gateway.service.definition.CustomDispatch;
import org.apache.hadoop.gateway.service.definition.RewriteFilter;
import org.apache.hadoop.gateway.service.definition.ServiceDefinition;
import org.apache.hadoop.gateway.service.definition.UrlBinding;
import org.apache.hadoop.gateway.topology.Provider;
import org.apache.hadoop.gateway.topology.Service;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceDefinitionDeploymentContributor extends ServiceDeploymentContributorBase {

  private static final String DISPATCH_ROLE = "dispatch";

  private static final String DISPATCH_IMPL_PARAM = "dispatch-impl";

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
    contributeRewriteRules(context, service);
    contributeResources(context, service);
  }

  private void contributeRewriteRules(DeploymentContext context, Service service) {
    if ( serviceRules != null ) {
      UrlRewriteRulesDescriptor clusterRules = context.getDescriptor("rewrite");
      clusterRules.addRules(serviceRules);
    }
  }

  private void contributeResources(DeploymentContext context, Service service) {
    Map<String, String> filterParams = new HashMap<String, String>();
    List<UrlBinding> bindings = serviceDefinition.getUrlBindings();
    for ( UrlBinding binding : bindings ) {
      List<RewriteFilter> filters = binding.getRewriteFilters();
      if ( filters != null && !filters.isEmpty() ) {
        filterParams.clear();
        for ( RewriteFilter filter : filters ) {
          filterParams.put(filter.getApplyTo(), filter.getRef());
        }
      }
      try {
        contributeResource(context, service, binding, filterParams);
      } catch ( URISyntaxException e ) {
        e.printStackTrace();
      }
    }

  }

  private void contributeResource(DeploymentContext context, Service service, UrlBinding binding, Map<String, String> filterParams) throws URISyntaxException {
    List<FilterParamDescriptor> params = new ArrayList<FilterParamDescriptor>();
    ResourceDescriptor resource = context.getGatewayDescriptor().addResource();
    resource.role(service.getRole());
    resource.pattern(binding.getPattern());
    addWebAppSecFilters(context, service, resource);
    addAuthenticationFilter(context, service, resource);
    addIdentityAssertionFilter(context, service, resource);
    addAuthorizationFilter(context, service, resource);
    if ( !filterParams.isEmpty() ) {
      for ( Map.Entry<String, String> filterParam : filterParams.entrySet() ) {
        params.add(resource.createFilterParam().name(filterParam.getKey()).value(filterParam.getValue()));
      }
    }
    addRewriteFilter(context, service, resource, params);
    addDispatchFilter(context, service, resource, binding);
  }

  private void addDispatchFilter(DeploymentContext context, Service service, ResourceDescriptor resource, UrlBinding binding) {
    CustomDispatch customDispatch = binding.getDispatch();
    if ( customDispatch == null ) {
      customDispatch = serviceDefinition.getDispatch();
    }
    if ( customDispatch != null ) {
      boolean isHaEnabled = isHaEnabled(context);
      if ( isHaEnabled && (customDispatch.getHaContributorName() != null) ) {
        addDispatchFilter(context, service, resource, DISPATCH_ROLE, customDispatch.getHaContributorName());
      } else {
        String contributorName = customDispatch.getContributorName();
        if ( contributorName != null ) {
          addDispatchFilter(context, service, resource, DISPATCH_ROLE, contributorName);
        } else {
          String className = customDispatch.getClassName();
          if ( className != null ) {
            FilterDescriptor filter = resource.addFilter().name(getName()).role(DISPATCH_ROLE).impl(GatewayDispatchFilter.class);
            filter.param().name(DISPATCH_IMPL_PARAM).value(className);
          }
        }
      }
    } else {
      addDispatchFilter(context, service, resource, DISPATCH_ROLE, "http-client");
    }
  }

  private boolean isHaEnabled(DeploymentContext context) {
    Provider provider = getProviderByRole(context, "ha");
    if ( provider != null && provider.isEnabled() ) {
      Map<String, String> params = provider.getParams();
      if ( params != null ) {
        if ( params.containsKey(getRole()) ) {
          return true;
        }
      }
    }
    return false;
  }

}
