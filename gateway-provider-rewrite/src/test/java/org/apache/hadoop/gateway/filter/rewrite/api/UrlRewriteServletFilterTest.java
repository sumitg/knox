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
package org.apache.hadoop.gateway.filter.rewrite.api;

import com.jayway.jsonassert.JsonAssert;
import org.apache.hadoop.gateway.filter.AbstractGatewayFilter;
import org.apache.hadoop.gateway.util.urltemplate.Parser;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.log.NoOpAppender;
import org.apache.hadoop.test.mock.MockInteraction;
import org.apache.hadoop.test.mock.MockServlet;
import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.util.ArrayQueue;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.xmlmatchers.XmlMatchers.hasXPath;
import static org.xmlmatchers.transform.XmlConverters.the;

public class UrlRewriteServletFilterTest {

  Logger LOG = Logger.getLogger(UrlRewriteServletFilterTest.class);

  private ServletTester server;
  private HttpTester.Request request;
  private HttpTester.Response response;
  private ArrayQueue<MockInteraction> interactions;
  private MockInteraction interaction;

  private static URL getTestResource( String name ) {
    name = UrlRewriteServletFilterTest.class.getName().replaceAll( "\\.", "/" ) + "/" + name;
    URL url = ClassLoader.getSystemResource( name );
    return url;
  }

  public void setUp( Map<String,String> initParams ) throws Exception {
    String descriptorUrl = getTestResource( "rewrite.xml" ).toExternalForm();

    server = new ServletTester();
    server.setContextPath( "/" );
    server.getContext().addEventListener( new UrlRewriteServletContextListener() );
    server.getContext().setInitParameter(
        UrlRewriteServletContextListener.DESCRIPTOR_LOCATION_INIT_PARAM_NAME, descriptorUrl );

    FilterHolder setupFilter = server.addFilter( SetupFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    setupFilter.setFilter( new SetupFilter() );
    FilterHolder rewriteFilter = server.addFilter( UrlRewriteServletFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    if( initParams != null ) {
      for( Map.Entry<String,String> entry : initParams.entrySet() ) {
        rewriteFilter.setInitParameter( entry.getKey(), entry.getValue() );
      }
    }
    rewriteFilter.setFilter( new UrlRewriteServletFilter() );

    interactions = new ArrayQueue<MockInteraction>();

    ServletHolder servlet = server.addServlet( MockServlet.class, "/" );
    servlet.setServlet( new MockServlet( "mock-servlet", interactions ) );

    server.start();

    interaction = new MockInteraction();
    request = HttpTester.newRequest();
    response = null;
  }

  @After
  public void tearDown() throws Exception {
    if( server != null ) {
      server.stop();
    }
  }

  @Test
  public void testInboundRequestUrlRewrite() throws Exception {
    setUp( null );
    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:1/test-output-path-1" );
    interaction.respond().status( 200 ).content( "test-response-content".getBytes() );
    interactions.add( interaction );
    // Create the client request.
    request.setMethod( "GET" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
    assertThat( response.getContent(), is( "test-response-content" ) );
  }

  @Test
  public void testInboundHeaderRewrite() throws Exception {
    setUp( null );
    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .header( "Location", "http://mock-host:1/test-output-path-1" );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    // Create the client request.
    request.setMethod( "GET" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Location", "http://mock-host:1/test-input-path" );
    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testOutboundHeaderRewrite() throws Exception {
    setUp( null );
    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:1/test-output-path-1" );
    interaction.respond()
        .status( 201 )
        .header( "Location", "http://mock-host:1/test-input-path" );
    interactions.add( interaction );
    // Create the client request.
    request.setMethod( "GET" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 201 ) );
    assertThat( response.get( HttpHeader.LOCATION ), is( "http://mock-host:1/test-output-path-1" ) );
  }

//  @Ignore( "Need to figure out how to handle cookies since domain and path are separate." )
//  @Test
//  public void testRequestCookieRewrite() throws Exception {
//    setUp( null );
//    // Setup the server side request/response interaction.
//    interaction.expect()
//        .method( "GET" )
//        .requestUrl( "http://mock-host:1/test-output-path-1" )
//        .header( "Cookie", "cookie-name=cookie-value; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 13-Jan-2021 22:23:01 GMT; Secure; HttpOnly" );
//    interaction.respond()
//        .status( 201 );
//    interactions.add( interaction );
//    // Create the client request.
//    request.setMethod( "GET" );
//    request.setURI( "/test-input-path" );
//    //request.setVersion( "HTTP/1.1" );
//    request.setHeader( "Host", "mock-host:1" );
//    request.setHeader( "Cookie", "cookie-name=cookie-value; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 13-Jan-2021 22:23:01 GMT; Secure; HttpOnly" );
//
//    // Execute the request.
//    response = TestUtils.execute( server, request );
//
//    // Test the results.
//    assertThat( response.getStatus(), is( 201 ) );
//    fail( "TODO" );
//  }

//  @Ignore( "Need to figure out how to handle cookies since domain and path are separate." )
//  @Test
//  public void testResponseCookieRewrite() throws Exception {
//    setUp( null );
//    // Setup the server side request/response interaction.
//    interaction.expect()
//        .method( "GET" )
//        .requestUrl( "http://mock-host:1/test-output-path-1" );
//    interaction.respond()
//        .status( 200 )
//        .header( "Set-Cookie", "cookie-name=cookie-value; Domain=docs.foo.com; Path=/accounts; Expires=Wed, 13-Jan-2021 22:23:01 GMT; Secure; HttpOnly" );
//    interactions.add( interaction );
//    // Create the client request.
//    request.setMethod( "GET" );
//    request.setURI( "/test-input-path" );
//    //request.setVersion( "HTTP/1.1" );
//    request.setHeader( "Host", "mock-host:1" );
//
//    // Execute the request.
//    response = TestUtils.execute( server, request );
//
//    // Test the results.
//    assertThat( response.getStatus(), is( 200 ) );
//    assertThat( response.get( HttpHeader.SET_COOKIE ), is( "TODO" ) );
//    fail( "TODO" );
//  }

  @Test
  public void testInboundJsonBodyRewrite() throws Exception {
    setUp( null );

    String inputJson = "{\"url\":\"http://mock-host:1/test-input-path\"}";
    String outputJson = "{\"url\":\"http://mock-host:1/test-output-path-1\"}";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .content( outputJson, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Content-Type", "application/json; charset=UTF-8" );
    request.setContent( inputJson );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testInboundXmlBodyRewrite() throws Exception {
    setUp( null );
    String input = "<root attribute=\"http://mock-host:1/test-input-path\">http://mock-host:1/test-input-path</root>";
    String output = null;
    if(System.getProperty("java.vendor").contains("IBM")){
      output = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><root attribute=\"http://mock-host:1/test-output-path-1\">http://mock-host:1/test-output-path-1</root>";
    }else {
      output = "<?xml version=\"1.0\" standalone=\"no\"?><root attribute=\"http://mock-host:1/test-output-path-1\">http://mock-host:1/test-output-path-1</root>";
    }
    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .content( output, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Content-Type", "application/xml; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  // MatcherAssert.assertThat( XmlConverters.the( outputHtml ), XmlMatchers.hasXPath( "/html" ) );
  @Test
  public void testOutboundJsonBodyRewrite() throws Exception {
    setUp( null );

    String input = "{\"url\":\"http://mock-host:1/test-input-path\"}";
    String expect = "{\"url\":\"http://mock-host:1/test-output-path-1\"}";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" );
    interaction.respond()
        .status( 200 )
        .contentType( "application/json" )
        .content( input, Charset.forName( "UTF-8" ) );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
    assertThat( response.getContent(), is( expect ) );
  }

  @Test
  public void testOutboundHtmlBodyRewrite() throws Exception {
    setUp( null );

    String input = "<html><head></head><body><a href=\"http://mock-host:1/test-input-path\">link text</a></body></html>";
    String output = "<html><head></head><body><a href=\"http://mock-host:1/test-output-path-1\">link text</a></body></html>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .content( output, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Content-Type", "application/html; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testInboundHtmlFormRewrite() throws Exception {
    setUp( null );

    String input = "Name=Jonathan+Doe&Age=23&Formula=a+%2B+b+%3D%3D+13%25%21&url=http%3A%2F%2Fmock-host%3A1%2Ftest-input-path";
    String expect = "Name=Jonathan+Doe&Age=23&Formula=a+%2B+b+%3D%3D+13%25%21&url=http%3A%2F%2Fmock-host%3A1%2Ftest-output-path-1";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .content( expect, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testRequestUrlRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.url", "test-rule-2" );
    setUp( initParams );

    String input = "<root/>";
    String expect = "<root/>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:42/test-output-path-2" )
        .contentType( "text/xml" )
        .characterEncoding( "UTF-8" )
        .content( expect, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );
    request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testRequestHeaderRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.headers", "test-filter-2" );
    setUp( initParams );

    String input = "<root/>";
    String expect = "<root/>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:42/test-output-path-1" )
        .contentType( "text/xml" )
        .characterEncoding( "UTF-8" )
        .content( expect, Charset.forName( "UTF-8" ) )
        .header( "Location", "http://mock-host:42/test-output-path-2" );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );
    request.setHeader( "Location", "http://mock-host:42/test-input-path-1" );
    request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

//  @Ignore( "Not Implemented Yet" )
//  @Test
//  public void testRequestCookieRewriteWithFilterInitParam() {
//    fail( "TODO" );
//  }

  @Test
  public void testRequestJsonBodyRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    //initParams.put( "url, "" );
    initParams.put( "request.body", "test-filter-2" );
    //initParams.put( "response", "" );
    setUp( initParams );

    String inputJson = "{\"url\":\"http://mock-host:42/test-input-path-1\"}";
    String expectJson = "{\"url\":\"http://mock-host:42/test-output-path-2\"}";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:42/test-output-path-1" )
        .contentType( "application/json" )
        .content( expectJson, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );
    request.setHeader( "Content-Type", "application/json; charset=UTF-8" );
    request.setContent( inputJson );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testRequestXmlBodyRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.body", "test-filter-2" );
    setUp( initParams );

    String input = "<root url='http://mock-host:42/test-input-path-1'><url>http://mock-host:42/test-input-path-1</url></root>";
    String expect = "<root url='http://mock-host:42/test-output-path-2'><url>http://mock-host:42/test-output-path-2</url></root>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:42/test-output-path-1" )
        .contentType( "text/xml" )
        .characterEncoding( "UTF-8" )
        .content( expect, Charset.forName( "UTF-8" ) );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );
    request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testRequestXmlBodyRewriteWithFilterInitParamForInvalidFilterConfig() throws Exception {
    Enumeration<Appender> realAppenders = NoOpAppender.setUp();
    try {

      Map<String,String> initParams = new HashMap<String,String>();
      initParams.put( "request.body", "test-filter-3" );
      setUp( initParams );

      String input = "<root url='http://mock-host:42/test-input-path-1'><url>http://mock-host:42/test-input-path-2</url></root>";
      String expect = "<root url='http://mock-host:42/test-input-path-2'><url>http://mock-host:42/test-input-path-2</url></root>";

      // Setup the server side request/response interaction.
      interaction.expect()
          .method( "PUT" )
          .requestUrl( "http://mock-host:42/test-output-path-1" )
          .contentType( "text/xml" )
          .characterEncoding( "UTF-8" )
          .content( expect, Charset.forName( "UTF-8" ) );
      interaction.respond()
          .status( 200 );
      interactions.add( interaction );
      request.setMethod( "PUT" );
      request.setURI( "/test-input-path" );
      //request.setVersion( "HTTP/1.1" );
      request.setHeader( "Host", "mock-host:42" );
      request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
      request.setContent( input );

      // Execute the request.
      response = TestUtils.execute( server, request );

      // Test the results.
      assertThat( response.getStatus(), is( 500 ) );
    } finally {
      NoOpAppender.tearDown( realAppenders );
    }
  }

  @Test
  public void testRequestFormBodyRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "request.body", "test-filter-2" );
    setUp( initParams );

    String input = "Name=Jonathan+Doe&Age=23&Formula=a+%2B+b+%3D%3D+13%25%21&url=http%3A%2F%2Fmock-host%3A1%2Ftest-input-path";
    String expect = "Name=Jonathan+Doe&Age=23&Formula=a+%2B+b+%3D%3D+13%25%21&url=http%3A%2F%2Fmock-host%3A1%2Ftest-output-path-2";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://mock-host:1/test-output-path-1" )
        .content( expect, Charset.forName( "UTF-8" ) )
        .characterEncoding( "UTF-8" );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:1" );
    request.setHeader( "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8" );
    request.setContent( input );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );
  }

  @Test
  public void testResponseHeaderRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "response.headers", "test-filter-2" );
    setUp( initParams );

    String output = "<root url='http://mock-host:42/test-input-path-2'><url>http://mock-host:42/test-input-path-3</url></root>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:42/test-output-path-1" );
    interaction.respond()
        .content( output, Charset.forName( "UTF-8" ) )
        .contentType( "text/xml" )
        .header( "Location", "http://mock-host:42/test-input-path-4" )
        .status( 307 );
    interactions.add( interaction );
    request.setMethod( "GET" );
    request.setURI( "/test-input-path-1" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 307 ) );
    assertThat( response.get( HttpHeader.LOCATION ), is( "http://mock-host:42/test-output-path-2" ) );

    String actual = response.getContent();

    assertThat( the( actual ), hasXPath( "/root/@url", equalTo( "http://mock-host:42/test-output-path-1" ) ) );
    assertThat( the( actual ), hasXPath( "/root/url/text()", equalTo( "http://mock-host:42/test-output-path-1" ) ) );
  }

//  @Ignore( "Not Implemented Yet" )
//  @Test
//  public void testResponseCookieRewriteWithFilterInitParam() {
//    fail( "TODO" );
//  }

  @Test
  public void testResponseJsonBodyRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    //initParams.put( "url, "" );
    initParams.put( "response.body", "test-filter-2" );
    //initParams.put( "response", "" );
    setUp( initParams );

    String responseJson = "{\"url\":\"http://mock-host:42/test-input-path-1\"}";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:42/test-output-path-1" );
    interaction.respond()
        .contentType( "application/json" )
        .content( responseJson, Charset.forName( "UTF-8" ) )
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "GET" );
    request.setURI( "/test-input-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "mock-host:42" );
    request.setHeader( "Content-Type", "application/json; charset=UTF-8" );
    request.setContent( responseJson );

    // Execute the request.
    response = TestUtils.execute( server, request );

    assertThat( response.getStatus(), is( 200 ) );
    JsonAssert.with( response.getContent() ).assertThat( "$.url", is( "http://mock-host:42/test-output-path-2" ) );
  }

  @Test
  public void testResponseXmlBodyRewriteWithFilterInitParam() throws Exception {
    Map<String,String> initParams = new HashMap<String,String>();
    initParams.put( "response.body", "test-filter-2" );
    setUp( initParams );

    String output = "<root url='http://mock-host:42/test-input-path-1'><url>http://mock-host:42/test-input-path-1</url></root>";

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "GET" )
        .requestUrl( "http://mock-host:42/test-output-path-1" );
    interaction.respond()
        .content( output, Charset.forName( "UTF-8" ) )
        .contentType( "text/xml" )
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "GET" );
    request.setURI( "/test-input-path" );
    request.setVersion( "HTTP/1.0" );
    request.setHeader( "Host", "mock-host:42" );

    // Execute the request.
    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), is( 200 ) );

    String actual = response.getContent();

    assertThat( the( actual ), hasXPath( "/root/@url", equalTo( "http://mock-host:42/test-output-path-2" ) ) );
    assertThat( the( actual ), hasXPath( "/root/url/text()", equalTo( "http://mock-host:42/test-output-path-2" ) ) );
  }

  private static class SetupFilter implements Filter {
    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( ServletRequest request, ServletResponse response, FilterChain chain ) throws IOException, ServletException {
      HttpServletRequest httpRequest = ((HttpServletRequest)request);
      StringBuffer sourceUrl = httpRequest.getRequestURL();
      String queryString = httpRequest.getQueryString();
      if( queryString != null ) {
        sourceUrl.append( "?" );
        sourceUrl.append( queryString );
      }
      try {
        request.setAttribute(
            AbstractGatewayFilter.SOURCE_REQUEST_URL_ATTRIBUTE_NAME,
            Parser.parseLiteral( sourceUrl.toString() ) );
      } catch( URISyntaxException e ) {
        throw new ServletException( e );
      }
      chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
  }

}
