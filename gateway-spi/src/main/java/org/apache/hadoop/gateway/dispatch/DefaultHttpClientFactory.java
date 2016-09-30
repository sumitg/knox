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
package org.apache.hadoop.gateway.dispatch;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.httpclient.HttpClientMetricNameStrategies;
import com.codahale.metrics.httpclient.HttpClientMetricNameStrategy;
import com.codahale.metrics.httpclient.InstrumentedHttpClientConnectionManager;
import com.codahale.metrics.httpclient.InstrumentedHttpClients;
import com.codahale.metrics.httpclient.InstrumentedHttpRequestExecutor;
import org.apache.hadoop.gateway.config.GatewayConfig;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.metrics.MetricsService;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

import javax.servlet.FilterConfig;
import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class DefaultHttpClientFactory implements HttpClientFactory {

  @Override
  public HttpClient createHttpClient(FilterConfig filterConfig) {
    GatewayServices services = (GatewayServices) filterConfig.getServletContext()
        .getAttribute(GatewayServices.GATEWAY_SERVICES_ATTRIBUTE);
    MetricsService metricsService = services.getService(GatewayServices.METRICS_SERVICE);
    HttpClientBuilder builder = metricsService.getInstrumented(HttpClientBuilder.class);

//        InstrumentedHttpClients.custom(registry, HttpClientMetricNameStrategies.QUERYLESS_URL_AND_METHOD);


//    HttpClientBuilder builder = HttpClients.custom();
    if ( "true".equals(System.getProperty(GatewayConfig.HADOOP_KERBEROS_SECURED)) ) {
      CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
      credentialsProvider.setCredentials(AuthScope.ANY, new UseJaasCredentials());

      Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
          .register(AuthSchemes.SPNEGO, new KnoxSpnegoAuthSchemeFactory(true))
          .build();

      builder = builder.setDefaultAuthSchemeRegistry(authSchemeRegistry)
          .setDefaultCookieStore(new HadoopAuthCookieStore())
          .setDefaultCredentialsProvider(credentialsProvider);
    } else {
      builder = builder.setDefaultCookieStore(new NoCookieStore());
    }

    builder.setKeepAliveStrategy( DefaultConnectionKeepAliveStrategy.INSTANCE );
    builder.setConnectionReuseStrategy( DefaultConnectionReuseStrategy.INSTANCE );
    builder.setRedirectStrategy( new NeverRedirectStrategy() );
    builder.setRetryHandler( new NeverRetryHandler() );

    int maxConnections = getMaxConnections( filterConfig );
    builder.setMaxConnTotal( maxConnections );
    builder.setMaxConnPerRoute( maxConnections );

    builder.setDefaultRequestConfig( getRequestConfig( filterConfig ) );

    HttpClient client = builder.build();
    return client;
  }

  private static RequestConfig getRequestConfig( FilterConfig config ) {
    RequestConfig.Builder builder = RequestConfig.custom();
    int connectionTimeout = getConnectionTimeout( config );
    if ( connectionTimeout != -1 ) {
      builder.setConnectTimeout( connectionTimeout );
      builder.setConnectionRequestTimeout( connectionTimeout );
    }
    int socketTimeout = getSocketTimeout( config );
    if( socketTimeout != -1 ) {
      builder.setSocketTimeout( socketTimeout );
    }
    return builder.build();
  }

  private class NoCookieStore implements CookieStore {
    @Override
    public void addCookie(Cookie cookie) {
      //no op
    }

    @Override
    public List<Cookie> getCookies() {
      return Collections.EMPTY_LIST;
    }

    @Override
    public boolean clearExpired(Date date) {
      return true;
    }

    @Override
    public void clear() {
      //no op
    }
  }

  private class NeverRedirectStrategy implements RedirectStrategy {
    @Override
    public boolean isRedirected( HttpRequest request, HttpResponse response, HttpContext context )
        throws ProtocolException {
      return false;
    }

    @Override
    public HttpUriRequest getRedirect( HttpRequest request, HttpResponse response, HttpContext context )
        throws ProtocolException {
      return null;
    }
  }

  private class NeverRetryHandler implements HttpRequestRetryHandler {
    @Override
    public boolean retryRequest( IOException exception, int executionCount, HttpContext context ) {
      return false;
    }
  }

  private static class UseJaasCredentials implements Credentials {

    public String getPassword() {
      return null;
    }

    public Principal getUserPrincipal() {
      return null;
    }

  }

  private int getMaxConnections( FilterConfig filterConfig ) {
    int maxConnections = 32;
    GatewayConfig config =
        (GatewayConfig)filterConfig.getServletContext().getAttribute( GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE );
    if( config != null ) {
      maxConnections = config.getHttpClientMaxConnections();
    }
    String str = filterConfig.getInitParameter( "httpclient.maxConnections" );
    if( str != null ) {
      try {
        maxConnections = Integer.parseInt( str );
      } catch ( NumberFormatException e ) {
        // Ignore it and use the default.
      }
    }
    return maxConnections;
  }

  private static int getConnectionTimeout( FilterConfig filterConfig ) {
    int timeout = -1;
    GatewayConfig globalConfig =
        (GatewayConfig)filterConfig.getServletContext().getAttribute( GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE );
    if( globalConfig != null ) {
      timeout = globalConfig.getHttpClientConnectionTimeout();
    }
    String str = filterConfig.getInitParameter( "httpclient.connectionTimeout" );
    if( str != null ) {
      try {
        timeout = (int)parseTimeout( str );
      } catch ( Exception e ) {
        // Ignore it and use the default.
      }
    }
    return timeout;
  }

  private static int getSocketTimeout( FilterConfig filterConfig ) {
    int timeout = -1;
    GatewayConfig globalConfig =
        (GatewayConfig)filterConfig.getServletContext().getAttribute( GatewayConfig.GATEWAY_CONFIG_ATTRIBUTE );
    if( globalConfig != null ) {
      timeout = globalConfig.getHttpClientSocketTimeout();
    }
    String str = filterConfig.getInitParameter( "httpclient.socketTimeout" );
    if( str != null ) {
      try {
        timeout = (int)parseTimeout( str );
      } catch ( Exception e ) {
        // Ignore it and use the default.
      }
    }
    return timeout;
  }

  private static long parseTimeout( String s ) {
    PeriodFormatter f = new PeriodFormatterBuilder()
        .appendMinutes().appendSuffix("m"," min")
        .appendSeconds().appendSuffix("s"," sec")
        .appendMillis().toFormatter();
    Period p = Period.parse( s, f );
    return p.toStandardDuration().getMillis();
  }

}
