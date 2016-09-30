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
package org.apache.hadoop.gateway.deploy;

import java.beans.Statement;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.apache.hadoop.gateway.GatewayMessages;
import org.apache.hadoop.gateway.GatewayServlet;
import org.apache.hadoop.gateway.config.GatewayConfig;
import org.apache.hadoop.gateway.deploy.impl.ApplicationDeploymentContributor;
import org.apache.hadoop.gateway.descriptor.GatewayDescriptor;
import org.apache.hadoop.gateway.descriptor.GatewayDescriptorFactory;
import org.apache.hadoop.gateway.i18n.messages.MessagesFactory;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.registry.ServiceRegistry;
import org.apache.hadoop.gateway.topology.Application;
import org.apache.hadoop.gateway.topology.Provider;
import org.apache.hadoop.gateway.topology.Service;
import org.apache.hadoop.gateway.topology.Topology;
import org.apache.hadoop.gateway.topology.Version;
import org.apache.hadoop.gateway.util.ServiceDefinitionsLoader;
import org.apache.hadoop.gateway.util.Urls;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptors;
import org.jboss.shrinkwrap.descriptor.api.webapp30.WebAppDescriptor;
import org.jboss.shrinkwrap.descriptor.api.webcommon30.FilterType;
import org.jboss.shrinkwrap.descriptor.api.webcommon30.ServletType;

public abstract class DeploymentFactory {

  private static final String SERVLET_NAME_SUFFIX = "-knox-gateway-servlet";
  private static final String FILTER_NAME_SUFFIX = "-knox-gateway-filter";
  private static GatewayMessages log = MessagesFactory.get( GatewayMessages.class );
  private static GatewayServices gatewayServices = null;

  private static Map<String,Map<String,Map<Version, ServiceDeploymentContributor>>> SERVICE_CONTRIBUTOR_MAP;
  static {
    loadServiceContributors();
  }

  private static Set<ProviderDeploymentContributor> PROVIDER_CONTRIBUTORS;
  private static Map<String,Map<String,ProviderDeploymentContributor>> PROVIDER_CONTRIBUTOR_MAP;
  static {
    loadProviderContributors();
  }

  public static void setGatewayServices(GatewayServices services) {
    DeploymentFactory.gatewayServices = services;
  }

  static List<Application> findApplicationsByUrl( Topology topology, String url ) {
    List<Application> foundApps = new ArrayList<Application>();
    if( topology != null ) {
      url = Urls.trimLeadingAndTrailingSlash( url );
      Collection<Application> searchApps = topology.getApplications();
      if( searchApps != null ) {
        for( Application searchApp : searchApps ) {
          List<String> searchUrls = searchApp.getUrls();
          if( searchUrls == null || searchUrls.isEmpty() ) {
            searchUrls = new ArrayList<String>(1);
            searchUrls.add( searchApp.getName() );
          }
          for( String searchUrl : searchUrls ) {
            if( url.equalsIgnoreCase( Urls.trimLeadingAndTrailingSlash( searchUrl ) ) ) {
              foundApps.add( searchApp );
              break;
            }
          }
        }
      }
    }
    return foundApps;
  }

  // Verify that there are no two apps with duplicate urls.
  static void validateNoAppsWithDuplicateUrlsInTopology( Topology topology ) {
    if( topology != null ) {
      Collection<Application> apps = topology.getApplications();
      if( apps != null ) {
        for( Application app : apps ) {
          List<String> urls = app.getUrls();
          if( urls == null || urls.isEmpty() ) {
            urls = new ArrayList<String>(1);
            urls.add( app.getName() );
          }
          for( String url : urls ) {
            List<Application> dups = findApplicationsByUrl( topology, url );
            if( dups != null ) {
              for( Application dup : dups ) {
                if( dup != app ) {
                  throw new DeploymentException( "Topology " + topology.getName() + " contains applications " + app.getName() + " and " + dup.getName() + " with the same url: " + url );
                }
              }
            }
          }
        }
      }
    }
  }

  // Verify that if there are services that there are no applications with a root url.
  static void validateNoAppsWithRootUrlsInServicesTopology( Topology topology ) {
    if( topology != null ) {
      if( topology.getServices() != null && !topology.getServices().isEmpty() ) {
        List<Application> dups = findApplicationsByUrl( topology, "/" );
        if( dups != null && !dups.isEmpty() ) {
          throw new DeploymentException( "Topology " + topology.getName() + " contains both services and an application " + dups.get( 0 ).getName() + " with a root url." );
        }
      }
    }
  }

  static void validateTopology( Topology topology ) {
    validateNoAppsWithRootUrlsInServicesTopology( topology );
    validateNoAppsWithDuplicateUrlsInTopology( topology );
  }

  public static EnterpriseArchive createDeployment( GatewayConfig config, Topology topology ) {
    validateTopology( topology );
    loadStacksServiceContributors( config );
    Map<String,List<ProviderDeploymentContributor>> providers = selectContextProviders( topology );
    Map<String,List<ServiceDeploymentContributor>> services = selectContextServices( topology );
    Map<String,ServiceDeploymentContributor> applications = selectContextApplications( config, topology );
    EnterpriseArchive ear = ShrinkWrap.create( EnterpriseArchive.class, topology.getName() );
    ear.addAsResource( toStringAsset( topology ), "topology.xml" );
    if( !services.isEmpty() ) {
      WebArchive war = createServicesDeployment( config, topology, providers, services );
      ear.addAsModule( war );
    }
    if( !applications.isEmpty() ) {
      for( Map.Entry<String, ServiceDeploymentContributor> application : applications.entrySet() ) {
        WebArchive war = createApplicationDeployment( config, topology, providers, application );
        ear.addAsModule( war );
      }
    }
    return ear;
  }

  private static WebArchive createServicesDeployment(
      GatewayConfig config,
      Topology topology,
      Map<String,List<ProviderDeploymentContributor>> providers,
      Map<String,List<ServiceDeploymentContributor>> services ) {
    DeploymentContext context = createDeploymentContext( config, "/", topology, providers );
    initialize( context, providers, services, null );
    contribute( context, providers, services, null );
    finalize( context, providers, services, null );
    return context.getWebArchive();
  }

  public static WebArchive createApplicationDeployment(
      GatewayConfig config,
      Topology topology,
      Map<String,List<ProviderDeploymentContributor>> providers,
      Map.Entry<String,ServiceDeploymentContributor> application ) {
    String appPath = "/" + Urls.trimLeadingAndTrailingSlash( application.getKey() );
    DeploymentContext context = createDeploymentContext( config, appPath, topology, providers );
    initialize( context, providers, null, application );
    contribute( context, providers, null, application );
    finalize( context, providers, null, application );
    return context.getWebArchive();
  }

  private static Asset toStringAsset( Topology topology ) {
    StringWriter writer = new StringWriter();
    String xml;
    try {
      Map<String,Object> properties = new HashMap<String,Object>(2);
      properties.put( "eclipselink-oxm-xml", "org/apache/hadoop/gateway/topology/topology_binding-xml.xml" );
      properties.put( "eclipselink.media-type", "application/xml" );
      JAXBContext jaxbContext = JAXBContext.newInstance( Topology.class.getPackage().getName(), Topology.class.getClassLoader() , properties );
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty( Marshaller.JAXB_FORMATTED_OUTPUT, true );
      marshaller.marshal( topology, writer );
      writer.close();
      xml = writer.toString();
    } catch (IOException e) {
      throw new DeploymentException( "Failed to marshall topology.", e );
    } catch (JAXBException e) {
      throw new DeploymentException( "Failed to marshall topology.", e );
    }
    StringAsset asset = new StringAsset( xml );
    return asset;
  }

  private static DeploymentContext createDeploymentContext(
      GatewayConfig config,
      String archivePath,
      Topology topology,
      Map<String,List<ProviderDeploymentContributor>> providers ) {
    archivePath = Urls.encode( archivePath );
    WebArchive webArchive = ShrinkWrap.create( WebArchive.class, archivePath );
    WebAppDescriptor webAppDesc = Descriptors.create( WebAppDescriptor.class );
    GatewayDescriptor gateway = GatewayDescriptorFactory.create();
    DeploymentContext context = new DeploymentContextImpl(
        config, topology, gateway, webArchive, webAppDesc, providers );
    return context;
  }

  // Scan through the providers in the topology.  Collect any named providers in their roles list.
  // Scan through all of the loaded providers.  For each that doesn't have an existing provider in the role
  // list add it.
  private static Map<String,List<ProviderDeploymentContributor>> selectContextProviders( Topology topology ) {
    Map<String,List<ProviderDeploymentContributor>> providers = new LinkedHashMap<String, List<ProviderDeploymentContributor>>();
    collectTopologyProviders( topology, providers );
    collectDefaultProviders( providers );
    return providers;
  }

  private static void collectTopologyProviders(
      Topology topology, Map<String, List<ProviderDeploymentContributor>> defaults ) {
    for( Provider provider : topology.getProviders() ) {
      String name = provider.getName();
      if( name != null ) {
        String role = provider.getRole();
        Map<String,ProviderDeploymentContributor> nameMap = PROVIDER_CONTRIBUTOR_MAP.get( role );
        if( nameMap != null ) {
          ProviderDeploymentContributor contributor = nameMap.get( name );
          // If there isn't a contributor with this role/name try to find a "*" contributor.
          if( contributor == null ) {
            nameMap = PROVIDER_CONTRIBUTOR_MAP.get( "*" );
            if( nameMap != null ) {
              contributor = nameMap.get( name );
            }
          }
          if( contributor != null ) {
            List list = defaults.get( role );
            if( list == null ) {
              list = new ArrayList( 1 );
              defaults.put( role, list );
            }
            if( !list.contains( contributor ) ) {
              list.add( contributor );
            }
          }
        }
      }
    }
  }

  private static void collectDefaultProviders( Map<String,List<ProviderDeploymentContributor>> defaults ) {
    for( ProviderDeploymentContributor contributor : PROVIDER_CONTRIBUTORS ) {
      String role = contributor.getRole();
      List<ProviderDeploymentContributor> list = defaults.get( role );
      if( list == null ) {
        list = new ArrayList<ProviderDeploymentContributor>();
        defaults.put( role, list );
      }
      if( list.isEmpty() ) {
        list.add( contributor );
      }
    }
  }

  // Scan through the services in the topology.
  // For each that we find add it to the list of service roles included in the topology.
  private static Map<String,List<ServiceDeploymentContributor>> selectContextServices( Topology topology ) {
    Map<String,List<ServiceDeploymentContributor>> defaults
        = new HashMap<String,List<ServiceDeploymentContributor>>();
    for( Service service : topology.getServices() ) {
      String role = service.getRole();
      ServiceDeploymentContributor contributor = getServiceContributor( role, service.getName(), service.getVersion() );
      if( contributor != null ) {
        List<ServiceDeploymentContributor> list = defaults.get( role );
        if( list == null ) {
          list = new ArrayList<ServiceDeploymentContributor>( 1 );
          defaults.put( role, list );
        }
        if( !list.contains( contributor ) ) {
          list.add( contributor );
        }
      }
    }
    return defaults;
  }

  private static Map<String,ServiceDeploymentContributor> selectContextApplications(
      GatewayConfig config, Topology topology ) {
    Map<String,ServiceDeploymentContributor> contributors = new HashMap<>();
    if( topology != null ) {
      for( Application application : topology.getApplications() ) {
        String name = application.getName();
        if( name == null || name.isEmpty() ) {
          throw new DeploymentException( "Topologies cannot contain an application without a name." );
        }
        ApplicationDeploymentContributor contributor = new ApplicationDeploymentContributor( config, application );
        List<String> urls = application.getUrls();
        if( urls == null || urls.isEmpty() ) {
          urls = new ArrayList<String>( 1 );
          urls.add( "/" + name );
        }
        for( String url : urls ) {
          if( url == null || url.isEmpty() || url.equals( "/" ) ) {
            if( !topology.getServices().isEmpty() ) {
              throw new DeploymentException( String.format(
                  "Topologies with services cannot contain an application (%s) with a root url.", name ) );
            }
          }
          contributors.put( url, contributor );
        }
      }
    }
    return contributors;
  }

  private static void initialize(
      DeploymentContext context,
      Map<String,List<ProviderDeploymentContributor>> providers,
      Map<String,List<ServiceDeploymentContributor>> services,
      Map.Entry<String,ServiceDeploymentContributor> applications ) {
    WebAppDescriptor wad = context.getWebAppDescriptor();
    String topoName = context.getTopology().getName();
    if( applications == null ) {
      String servletName = topoName + SERVLET_NAME_SUFFIX;
      wad.createServlet().servletName( servletName ).servletClass( GatewayServlet.class.getName() );
      wad.createServletMapping().servletName( servletName ).urlPattern( "/*" );
    } else {
      String filterName = topoName + FILTER_NAME_SUFFIX;
      wad.createFilter().filterName( filterName ).filterClass( GatewayServlet.class.getName() );
      wad.createFilterMapping().filterName( filterName ).urlPattern( "/*" );
    }
    if (gatewayServices != null) {
      gatewayServices.initializeContribution(context);
    } else {
      log.gatewayServicesNotInitialized();
    }
    initializeProviders( context, providers );
    initializeServices( context, services );
    initializeApplications( context, applications );
  }

  private static void initializeProviders(
      DeploymentContext context,
      Map<String,List<ProviderDeploymentContributor>> providers ) {
    if( providers != null ) {
      for( String role : providers.keySet() ) {
        for( ProviderDeploymentContributor contributor : providers.get( role ) ) {
          try {
            injectServices( contributor );
            log.initializeProvider( contributor.getName(), contributor.getRole() );
            contributor.initializeContribution( context );
          } catch( Exception e ) {
            log.failedToInitializeContribution( e );
            throw new DeploymentException( "Failed to initialize contribution.", e );
          }
        }
      }
    }
  }

  private static void initializeServices( DeploymentContext context, Map<String, List<ServiceDeploymentContributor>> services ) {
    if( services != null ) {
      for( String role : services.keySet() ) {
        for( ServiceDeploymentContributor contributor : services.get( role ) ) {
          try {
            injectServices( contributor );
            log.initializeService( contributor.getName(), contributor.getRole() );
            contributor.initializeContribution( context );
          } catch( Exception e ) {
            log.failedToInitializeContribution( e );
            throw new DeploymentException( "Failed to initialize contribution.", e );
          }
        }
      }
    }
  }

  private static void initializeApplications( DeploymentContext context, Map.Entry<String, ServiceDeploymentContributor> application ) {
    if( application != null ) {
      ServiceDeploymentContributor contributor = application.getValue();
      if( contributor != null ) {
        try {
          injectServices( contributor );
          log.initializeApplication( contributor.getName() );
          contributor.initializeContribution( context );
        } catch( Exception e ) {
          log.failedToInitializeContribution( e );
          throw new DeploymentException( "Failed to initialize application contribution.", e );
        }
      }
    }
  }

  private static void injectServices(Object contributor) {
    if (gatewayServices != null) {
      Statement stmt = null;
      for(String serviceName : gatewayServices.getServiceNames()) {

        try {
          // TODO: this is just a temporary injection solution
          // TODO: test for the existence of the setter before attempting it
          // TODO: avoid exception throwing when there is no setter
          stmt = new Statement(contributor, "set" + serviceName, new Object[]{gatewayServices.getService(serviceName)});
          stmt.execute();
        } catch (NoSuchMethodException e) {
          // TODO: eliminate the possibility of this being thrown up front
        } catch (Exception e) {
          // Maybe it makes sense to throw exception
          log.failedToInjectService( serviceName, e );
          throw new DeploymentException("Failed to inject service.", e);
        }
      }
    }
  }

  private static void contribute(
      DeploymentContext context,
      Map<String,List<ProviderDeploymentContributor>> providers,
      Map<String,List<ServiceDeploymentContributor>> services,
      Map.Entry<String,ServiceDeploymentContributor> applications ) {
    Topology topology = context.getTopology();
    contributeProviders( context, topology, providers );
    contributeServices( context, topology, services );
    contributeApplications( context, topology, applications );
  }

  private static void contributeProviders( DeploymentContext context, Topology topology, Map<String, List<ProviderDeploymentContributor>> providers ) {
    for( Provider provider : topology.getProviders() ) {
      ProviderDeploymentContributor contributor = getProviderContributor( providers, provider.getRole(), provider.getName() );
      if( contributor != null && provider.isEnabled() ) {
        try {
          log.contributeProvider( provider.getName(), provider.getRole() );
          contributor.contributeProvider( context, provider );
        } catch( Exception e ) {
          // Maybe it makes sense to throw exception
          log.failedToContributeProvider( provider.getName(), provider.getRole(), e );
          throw new DeploymentException("Failed to contribute provider.", e);
        }
      }
    }
  }

  private static void contributeServices( DeploymentContext context, Topology topology, Map<String, List<ServiceDeploymentContributor>> services ) {
    if( services != null ) {
      for( Service service : topology.getServices() ) {
        ServiceDeploymentContributor contributor = getServiceContributor( service.getRole(), service.getName(), service.getVersion() );
        if( contributor != null ) {
          try {
            log.contributeService( service.getName(), service.getRole() );
            contributor.contributeService( context, service );
            if( gatewayServices != null ) {
              ServiceRegistry sr = gatewayServices.getService( GatewayServices.SERVICE_REGISTRY_SERVICE );
              if( sr != null ) {
                String regCode = sr.getRegistrationCode( topology.getName() );
                sr.registerService( regCode, topology.getName(), service.getRole(), service.getUrls() );
              }
            }
          } catch( Exception e ) {
            // Maybe it makes sense to throw exception
            log.failedToContributeService( service.getName(), service.getRole(), e );
            throw new DeploymentException( "Failed to contribute service.", e );
          }
        }
      }
    }
  }

  private static void contributeApplications( DeploymentContext context, Topology topology, Map.Entry<String, ServiceDeploymentContributor> applications ) {
    if( applications != null ) {
      ServiceDeploymentContributor contributor = applications.getValue();
      if( contributor != null ) {
        try {
          log.contributeApplication( contributor.getName() );
          Application applicationDesc = topology.getApplication( applications.getKey() );
          contributor.contributeService( context, applicationDesc );
        } catch( Exception e ) {
          log.failedToInitializeContribution( e );
          throw new DeploymentException( "Failed to contribution application.", e );
        }
      }
    }
  }

  public static ProviderDeploymentContributor getProviderContributor( String role, String name ) {
    ProviderDeploymentContributor contributor = null;
    Map<String,ProviderDeploymentContributor> nameMap = PROVIDER_CONTRIBUTOR_MAP.get( role );
    if( nameMap != null ) {
      if( name != null ) {
        contributor = nameMap.get( name );
      } else if ( !nameMap.isEmpty() ) {
        contributor = nameMap.values().iterator().next();
      }
    }
    return contributor;
  }

  public static ServiceDeploymentContributor getServiceContributor( String role, String name, Version version ) {
    ServiceDeploymentContributor contributor = null;
    Map<String,Map<Version, ServiceDeploymentContributor>> nameMap = SERVICE_CONTRIBUTOR_MAP.get( role );
    if( nameMap != null && !nameMap.isEmpty()) {
      Map<Version, ServiceDeploymentContributor> versionMap = null;
      if ( name == null ) {
        versionMap = nameMap.values().iterator().next();
      } else {
        versionMap = nameMap.get( name );
      }
      if ( versionMap != null && !versionMap.isEmpty()) {
        if( version == null ) {
          contributor = ((TreeMap<Version, ServiceDeploymentContributor>) versionMap).firstEntry().getValue();
        } else {
          contributor = versionMap.get( version );
        }
      }
    }
    return contributor;
  }

  private static void finalize(
      DeploymentContext context,
      Map<String,List<ProviderDeploymentContributor>> providers,
      Map<String,List<ServiceDeploymentContributor>> services,
      Map.Entry<String,ServiceDeploymentContributor> application ) {
    try {
      // Write the gateway descriptor (gateway.xml) into the war.
      StringWriter writer = new StringWriter();
      GatewayDescriptorFactory.store( context.getGatewayDescriptor(), "xml", writer );
      context.getWebArchive().addAsWebInfResource(
          new StringAsset( writer.toString() ),
          GatewayServlet.GATEWAY_DESCRIPTOR_LOCATION_DEFAULT );

      // Set the location of the gateway descriptor as a servlet init param.
      if( application == null ) {
        String servletName = context.getTopology().getName() + SERVLET_NAME_SUFFIX;
        ServletType<WebAppDescriptor> servlet = findServlet( context, servletName );
        // Coverity CID 1352314
        if( servlet == null ) {
          throw new DeploymentException( "Missing servlet " + servletName );
        } else {
          servlet.createInitParam()
              .paramName( GatewayServlet.GATEWAY_DESCRIPTOR_LOCATION_PARAM )
              .paramValue( "/WEB-INF/" + GatewayServlet.GATEWAY_DESCRIPTOR_LOCATION_DEFAULT );
        }
      } else {
        String servletName = context.getTopology().getName() + FILTER_NAME_SUFFIX;
        FilterType<WebAppDescriptor> filter = findFilter( context, servletName );
        // Coverity CID 1352313
        if( filter == null ) {
          throw new DeploymentException( "Missing filter " + servletName );
        } else {
          filter.createInitParam()
              .paramName( GatewayServlet.GATEWAY_DESCRIPTOR_LOCATION_PARAM )
              .paramValue( "/WEB-INF/" + GatewayServlet.GATEWAY_DESCRIPTOR_LOCATION_DEFAULT );
        }
      }
      if (gatewayServices != null) {
        gatewayServices.finalizeContribution(context);
      }
      finalizeProviders( context, providers );
      finalizeServices( context, services );
      finalizeApplications( context, application );
      writeDeploymentDescriptor( context, application != null );
    } catch ( IOException e ) {
      throw new RuntimeException( e );
    }
  }

  private static void finalizeProviders( DeploymentContext context, Map<String, List<ProviderDeploymentContributor>> providers ) {
    if( providers != null ) {
      for( String role : providers.keySet() ) {
        for( ProviderDeploymentContributor contributor : providers.get( role ) ) {
          try {
            log.finalizeProvider( contributor.getName(), contributor.getRole() );
            contributor.finalizeContribution( context );
          } catch( Exception e ) {
            // Maybe it makes sense to throw exception
            log.failedToFinalizeContribution( e );
            throw new DeploymentException( "Failed to finalize contribution.", e );
          }
        }
      }
    }
  }

  private static void finalizeServices( DeploymentContext context, Map<String, List<ServiceDeploymentContributor>> services ) {
    if( services != null ) {
      for( String role : services.keySet() ) {
        for( ServiceDeploymentContributor contributor : services.get( role ) ) {
          try {
            log.finalizeService( contributor.getName(), contributor.getRole() );
            contributor.finalizeContribution( context );
          } catch( Exception e ) {
            // Maybe it makes sense to throw exception
            log.failedToFinalizeContribution( e );
            throw new DeploymentException( "Failed to finalize contribution.", e );
          }
        }
      }
    }
  }

  private static void finalizeApplications( DeploymentContext context, Map.Entry<String, ServiceDeploymentContributor> application ) {
    if( application != null ) {
      ServiceDeploymentContributor contributor = application.getValue();
      if( contributor != null ) {
        try {
          log.finalizeApplication( contributor.getName() );
          contributor.finalizeContribution( context );
        } catch( Exception e ) {
          log.failedToInitializeContribution( e );
          throw new DeploymentException( "Failed to contribution application.", e );
        }
      }
    }
  }

  private static void writeDeploymentDescriptor( DeploymentContext context, boolean override ) {
    // Write the web.xml into the war.
    Asset webXmlAsset = new StringAsset( context.getWebAppDescriptor().exportAsString() );
    if( override ) {
      context.getWebArchive().addAsWebInfResource( webXmlAsset, "override-web.xml" );
    } else {
      context.getWebArchive().setWebXML( webXmlAsset );
    }
  }

  public static ServletType<WebAppDescriptor> findServlet( DeploymentContext context, String name ) {
    List<ServletType<WebAppDescriptor>> servlets = context.getWebAppDescriptor().getAllServlet();
    for( ServletType<WebAppDescriptor> servlet : servlets ) {
      if( name.equals( servlet.getServletName() ) ) {
        return servlet;
      }
    }
    return null;
  }

  public static FilterType<WebAppDescriptor> findFilter( DeploymentContext context, String name ) {
    List<FilterType<WebAppDescriptor>> filters = context.getWebAppDescriptor().getAllFilter();
    for( FilterType<WebAppDescriptor> filter : filters ) {
      if( name.equals( filter.getFilterName() ) ) {
        return filter;
      }
    }
    return null;
  }

  private static void loadStacksServiceContributors( GatewayConfig config ) {
    String stacks = config.getGatewayServicesDir();
    log.usingServicesDirectory(stacks);
    File stacksDir = new File(stacks);
    Set<ServiceDeploymentContributor> deploymentContributors = ServiceDefinitionsLoader.loadServiceDefinitions(stacksDir);
    addServiceDeploymentContributors(deploymentContributors.iterator());
  }

  private static void loadServiceContributors() {
    SERVICE_CONTRIBUTOR_MAP = new HashMap<String, Map<String, Map<Version, ServiceDeploymentContributor>>>();
    ServiceLoader<ServiceDeploymentContributor> loader = ServiceLoader.load( ServiceDeploymentContributor.class );
    Iterator<ServiceDeploymentContributor> contributors = loader.iterator();
    addServiceDeploymentContributors(contributors);
  }

   private static void addServiceDeploymentContributors(Iterator<ServiceDeploymentContributor> contributors) {
      while( contributors.hasNext() ) {
        ServiceDeploymentContributor contributor = contributors.next();
        if( contributor.getName() == null ) {
          log.ignoringServiceContributorWithMissingName( contributor.getClass().getName() );
          continue;
        }
        if( contributor.getRole() == null ) {
          log.ignoringServiceContributorWithMissingRole( contributor.getClass().getName() );
          continue;
        }
        if( contributor.getVersion() == null ) {
          log.ignoringServiceContributorWithMissingVersion(contributor.getClass().getName());
          continue;
        }
        Map<String,Map<Version, ServiceDeploymentContributor>> nameMap = SERVICE_CONTRIBUTOR_MAP.get( contributor.getRole() );
        if( nameMap == null ) {
          nameMap = new HashMap<String,Map<Version, ServiceDeploymentContributor>>();
          SERVICE_CONTRIBUTOR_MAP.put( contributor.getRole(), nameMap );
        }
        Map<Version, ServiceDeploymentContributor> versionMap = nameMap.get(contributor.getName());
        if (versionMap == null) {
          versionMap = new TreeMap<Version, ServiceDeploymentContributor>();
          nameMap.put(contributor.getName(), versionMap);
        }
        versionMap.put( contributor.getVersion(), contributor );
      }
   }

   private static void loadProviderContributors() {
    Set<ProviderDeploymentContributor> set = new HashSet<ProviderDeploymentContributor>();
    Map<String,Map<String,ProviderDeploymentContributor>> roleMap
        = new HashMap<String,Map<String,ProviderDeploymentContributor>>();

    ServiceLoader<ProviderDeploymentContributor> loader = ServiceLoader.load( ProviderDeploymentContributor.class );
    Iterator<ProviderDeploymentContributor> contributors = loader.iterator();
    while( contributors.hasNext() ) {
      ProviderDeploymentContributor contributor = contributors.next();
      if( contributor.getName() == null ) {
        log.ignoringProviderContributorWithMissingName( contributor.getClass().getName() );
        continue;
      }
      if( contributor.getRole() == null ) {
        log.ignoringProviderContributorWithMissingRole( contributor.getClass().getName() );
        continue;
      }
      set.add( contributor );
      Map nameMap = roleMap.get( contributor.getRole() );
      if( nameMap == null ) {
        nameMap = new HashMap<String,ProviderDeploymentContributor>();
        roleMap.put( contributor.getRole(), nameMap );
      }
      nameMap.put( contributor.getName(), contributor );
    }
    PROVIDER_CONTRIBUTORS = set;
    PROVIDER_CONTRIBUTOR_MAP = roleMap;
  }

  static ProviderDeploymentContributor getProviderContributor(
      Map<String,List<ProviderDeploymentContributor>> providers, String role, String name ) {
    ProviderDeploymentContributor contributor = null;
    if( name == null ) {
      List<ProviderDeploymentContributor> list = providers.get( role );
      if( list != null && !list.isEmpty() ) {
        contributor = list.get( 0 );
      }
    } else {
      contributor = getProviderContributor( role, name );
      // Explicit configuration that is wrong should just fail
      // rather than randomly select a provider. Implicit default
      // providers can be selected when no name is provided.
      if (name != null) {
        if (contributor == null || !contributor.getRole().equals(role) ||
            !contributor.getName().equals(name)) {
          throw new DeploymentException(
              "Failed to contribute provider. Role: " +
              role + " Name: " + name + ". Please check the topology for" +
              		" errors in name and role and that the provider is " +
              		"on the classpath.");
        }
      }
    }
    return contributor;
  }
}
