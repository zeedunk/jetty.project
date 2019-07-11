//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractClusteredOrphanedSessionTest
 *
 * Mimic node1 creating a session then crashing. Check that node2 will
 * eventually scavenge the orphaned session, even if the session was
 * never used on node2.
 */
public abstract class AbstractClusteredOrphanedSessionTest extends AbstractTestBase
{

    /**
     * @throws Exception on test failure
     */
    @Test
    public void testOrphanedSession() throws Exception
    {
        // Disable scavenging for the first server, so that we simulate its "crash".
        String contextPath = "/";
        String servletMapping = "/server";
        int inactivePeriod = 5;
        DefaultSessionCacheFactory cacheFactory1 = new DefaultSessionCacheFactory();
        cacheFactory1.setEvictionPolicy(SessionCache.NEVER_EVICT);
        SessionDataStoreFactory storeFactory1 = createSessionDataStoreFactory();
        if (storeFactory1 instanceof AbstractSessionDataStoreFactory)
        {
            ((AbstractSessionDataStoreFactory)storeFactory1).setGracePeriodSec(0);
        }

        TestServer server1 = new TestServer(0, inactivePeriod, -1, cacheFactory1, storeFactory1);
        server1.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
        try
        {
            server1.start();
            int port1 = server1.getPort();
            int scavengePeriod = 2;

            DefaultSessionCacheFactory cacheFactory2 = new DefaultSessionCacheFactory();
            SessionDataStoreFactory storeFactory2 = createSessionDataStoreFactory();
            if (storeFactory2 instanceof AbstractSessionDataStoreFactory)
            {
                ((AbstractSessionDataStoreFactory)storeFactory2).setGracePeriodSec(0);
            }
            TestServer server2 = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory2, storeFactory2);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);
            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Connect to server1 to create a session and get its session cookie
                    ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping.substring(1) + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");

                    // Wait for the session to expire.
                    // The first node does not do any scavenging, but the session
                    // must be removed by scavenging done in the other node.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(inactivePeriod + 2L * scavengePeriod));

                    // Perform one request to server2 to be sure that the session has been expired
                    Request request = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping.substring(1) + "?action=check");
                    request.header("Cookie", sessionCookie);
                    ContentResponse response2 = request.send();
                    assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("A", "A");
            }
            else if ("remove".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();
                //assertTrue(session == null);
            }
            else if ("check".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertTrue(session == null);
            }
        }
    }
}
