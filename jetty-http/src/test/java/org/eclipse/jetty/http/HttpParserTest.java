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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.http.HttpParser.State;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpComplianceSection.NO_FIELD_FOLDING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpParserTest
{
    static
    {
        HttpCompliance.CUSTOM0.sections().remove(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME);
    }

    /**
     * Parse until {@link State#END} state.
     * If the parser is already in the END state, then it is {@link HttpParser#reset()} and re-parsed.
     *
     * @param parser The parser to test
     * @param buffer the buffer to parse
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public static void parseAll(HttpParser parser, ByteBuffer buffer)
    {
        if (parser.isState(State.END))
            parser.reset();
        if (!parser.isState(State.START))
            throw new IllegalStateException("!START");

        // continue parsing
        int remaining = buffer.remaining();
        while (!parser.isState(State.END) && remaining > 0)
        {
            int was_remaining = remaining;
            parser.parseNext(buffer);
            remaining = buffer.remaining();
            if (remaining == was_remaining)
                break;
        }
    }

    @Test
    public void HttpMethodTest()
    {
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer("Wibble ")));
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer("GET")));
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer("MO")));

        assertEquals(HttpMethod.GET, HttpMethod.lookAheadGet(BufferUtil.toBuffer("GET ")));
        assertEquals(HttpMethod.MOVE, HttpMethod.lookAheadGet(BufferUtil.toBuffer("MOVE ")));

        ByteBuffer b = BufferUtil.allocateDirect(128);
        BufferUtil.append(b, BufferUtil.toBuffer("GET"));
        assertNull(HttpMethod.lookAheadGet(b));

        BufferUtil.append(b, BufferUtil.toBuffer(" "));
        assertEquals(HttpMethod.GET, HttpMethod.lookAheadGet(b));
    }

    @Test
    public void testLineParseMockIP() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /mock/127.0.0.1 HTTP/1.1\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/mock/127.0.0.1", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo HTTP/1.0\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse1RFC2616() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/999", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);
        assertThat(_complianceViolation, contains(HttpComplianceSection.NO_HTTP_0_9));
    }

    @Test
    public void testLineParse1() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testLineParse2RFC2616() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222  \r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/222", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);
        assertThat(_complianceViolation, contains(HttpComplianceSection.NO_HTTP_0_9));
    }

    @Test
    public void testLineParse2() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222  \r\n");

        _versionOrReason = null;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testLineParse3() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0\r\n" + "\r\n", StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/fo\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse4() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0\r\n" + "\r\n", StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo?param=\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLongURLParse() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/ HTTP/1.0\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testAllowedLinePreamble() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("\r\n\r\nGET / HTTP/1.0\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testDisallowedLinePreamble() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("\r\n \r\nGET / HTTP/1.0\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("Illegal character SPACE=' '", _bad);
    }

    @Test
    public void testConnect() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1\r\n" + "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("CONNECT", _methodOrVersion);
        assertEquals("192.168.1.2:80", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testSimple() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
    }

    @Test
    public void testFoldedField2616() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name: value\r\n" +
                " extra\r\n" +
                "Name2: \r\n" +
                "\tvalue2\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.nullValue());
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals(2, _headers);
        assertEquals("Name", _hdr[1]);
        assertEquals("value extra", _val[1]);
        assertEquals("Name2", _hdr[2]);
        assertEquals("value2", _val[2]);
        assertThat(_complianceViolation, contains(NO_FIELD_FOLDING, NO_FIELD_FOLDING));
    }

    @Test
    public void testFoldedField7230() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name: value\r\n" +
                " extra\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Header Folding"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testWhiteSpaceInName() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "N ame: value\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @Test
    public void testWhiteSpaceAfterName() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name : value\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @Test // TODO: Parameterize Test
    public void testWhiteSpaceBeforeRequest()
    {
        HttpCompliance[] compliances = new HttpCompliance[]
            {
                HttpCompliance.RFC7230, HttpCompliance.RFC2616
            };

        String[][] whitespaces = new String[][]
            {
                {" ", "Illegal character SPACE"},
                {"\t", "Illegal character HTAB"},
                {"\n", null},
                {"\r", "Bad EOL"},
                {"\r\n", null},
                {"\r\n\r\n", null},
                {"\r\n \r\n", "Illegal character SPACE"},
                {"\r\n\t\r\n", "Illegal character HTAB"},
                {"\r\t\n", "Bad EOL"},
                {"\r\r\n", "Bad EOL"},
                {"\t\r\t\r\n", "Illegal character HTAB"},
                {" \t \r \t \n\n", "Illegal character SPACE"},
                {" \r \t \r\n\r\n\r\n", "Illegal character SPACE"}
            };

        for (int i = 0; i < compliances.length; i++)
        {
            HttpCompliance compliance = compliances[i];

            for (int j = 0; j < whitespaces.length; j++)
            {
                String request =
                    whitespaces[j][0] +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Name: value" + j + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

                ByteBuffer buffer = BufferUtil.toBuffer(request);
                HttpParser.RequestHandler handler = new Handler();
                HttpParser parser = new HttpParser(handler, 4096, compliance);
                _bad = null;
                parseAll(parser, buffer);

                String test = "whitespace.[" + compliance + "].[" + j + "]";
                String expected = whitespaces[j][1];
                if (expected == null)
                    assertThat(test, _bad, is(nullValue()));
                else
                    assertThat(test, _bad, containsString(expected));
            }
        }
    }

    @Test
    public void testNoValue() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name0: \r\n" +
                "Name1:\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Name0", _hdr[1]);
        assertEquals("", _val[1]);
        assertEquals("Name1", _hdr[2]);
        assertEquals("", _val[2]);
        assertEquals(2, _headers);
    }

    @Test
    public void testSpaceinNameCustom0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name with space: value\r\n" +
                "Other: value\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.CUSTOM0);
        parseAll(parser, buffer);

        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, contains(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME));
    }

    @Test
    public void testNoColonCustom0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name \r\n" +
                "Other: value\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.CUSTOM0);
        parseAll(parser, buffer);

        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, contains(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME));
    }

    @Test
    public void testTrailingSpacesInHeaderNameInCustom0Mode() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Headers : Origin\r\n" +
                "Other\t : value\r\n" +
                "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.CUSTOM0);
        parseAll(parser, buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No Content", _versionOrReason);
        assertEquals(null, _content);

        assertEquals(1, _headers);
        System.out.println(Arrays.asList(_hdr));
        System.out.println(Arrays.asList(_val));
        assertEquals("Access-Control-Allow-Headers", _hdr[0]);
        assertEquals("Origin", _val[0]);
        assertEquals("Other", _hdr[1]);
        assertEquals("value", _val[1]);

        assertThat(_complianceViolation, contains(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME, HttpComplianceSection.NO_WS_AFTER_FIELD_NAME));
    }

    @Test
    public void testTrailingSpacesInHeaderNameNoCustom0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Headers : Origin\r\n" +
                "Other: value\r\n" +
                "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No Content", _versionOrReason);
        assertThat(_bad, containsString("Illegal character "));
    }

    @Test
    public void testNoColon7230() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testHeaderParseDirect() throws Exception
    {
        ByteBuffer b0 = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "HostHeader: notHost\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");
        ByteBuffer buffer = BufferUtil.allocateDirect(b0.capacity());
        int pos = BufferUtil.flipToFill(buffer);
        BufferUtil.put(b0, buffer);
        BufferUtil.flipToFlush(buffer, pos);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @Test
    public void testHeaderParseCRLF() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "HostHeader: notHost\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @Test
    public void testHeaderParseLF() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\n" +
                "Host: localhost\n" +
                "Header1: value1\n" +
                "Header2:   value 2a value 2b  \n" +
                "Header3: 3\n" +
                "Header4:value4\n" +
                "Server5: notServer\n" +
                "HostHeader: notHost\n" +
                "Connection: close\n" +
                "Accept-Encoding: gzip, deflated\n" +
                "Accept: unknown\n" +
                "\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a value 2b", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @Test
    public void testQuoted() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\n" +
                "Name0: \"value0\"\t\n" +
                "Name1: \"value\t1\"\n" +
                "Name2: \"value\t2A\",\"value,2B\"\t\n" +
                "\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Name0", _hdr[0]);
        assertEquals("\"value0\"", _val[0]);
        assertEquals("Name1", _hdr[1]);
        assertEquals("\"value\t1\"", _val[1]);
        assertEquals("Name2", _hdr[2]);
        assertEquals("\"value\t2A\",\"value,2B\"", _val[2]);
        assertEquals(2, _headers);
    }

    @Test
    public void testEncodedHeader() throws Exception
    {
        ByteBuffer buffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(buffer);
        BufferUtil.put(BufferUtil.toBuffer("GET "), buffer);
        buffer.put("/foo/\u0690/".getBytes(StandardCharsets.UTF_8));
        BufferUtil.put(BufferUtil.toBuffer(" HTTP/1.0\r\n"), buffer);
        BufferUtil.put(BufferUtil.toBuffer("Header1: "), buffer);
        buffer.put("\u00e6 \u00e6".getBytes(StandardCharsets.ISO_8859_1));
        BufferUtil.put(BufferUtil.toBuffer("  \r\nHeader2: "), buffer);
        buffer.put((byte)-1);
        BufferUtil.put(BufferUtil.toBuffer("\r\n\r\n"), buffer);
        BufferUtil.flipToFlush(buffer, 0);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/foo/\u0690/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Header1", _hdr[0]);
        assertEquals("\u00e6 \u00e6", _val[0]);
        assertEquals("Header2", _hdr[1]);
        assertEquals("" + (char)255, _val[1]);
        assertEquals(1, _headers);
        assertEquals(null, _bad);
    }

    @Test
    public void testResponseBufferUpgradeFrom() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 Upgrade\r\n" +
                "Connection: upgrade\r\n" +
                "Content-Length: 0\r\n" +
                "Sec-WebSocket-Accept: 4GnyoUP4Sc1JD+2pCbNYAhFYVVA\r\n" +
                "\r\n" +
                "FOOGRADE");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        while (!parser.isState(State.END))
        {
            parser.parseNext(buffer);
        }

        assertThat(BufferUtil.toUTF8String(buffer), Matchers.is("FOOGRADE"));
    }

    @Test
    public void testBadMethodEncoding() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "G\u00e6T / HTTP/1.0\r\nHeader0: value0\r\n\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test
    public void testBadVersionEncoding() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / H\u00e6P/1.0\r\nHeader0: value0\r\n\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test
    public void testBadHeaderEncoding() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                    "H\u00e6der0: value0\r\n" +
                    "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test // TODO: Parameterize Test
    public void testBadHeaderNames() throws Exception
    {
        String[] bad = new String[]
            {
                "Foo\\Bar: value\r\n",
                "Foo@Bar: value\r\n",
                "Foo,Bar: value\r\n",
                "Foo}Bar: value\r\n",
                "Foo{Bar: value\r\n",
                "Foo=Bar: value\r\n",
                "Foo>Bar: value\r\n",
                "Foo<Bar: value\r\n",
                "Foo)Bar: value\r\n",
                "Foo(Bar: value\r\n",
                "Foo?Bar: value\r\n",
                "Foo\"Bar: value\r\n",
                "Foo/Bar: value\r\n",
                "Foo]Bar: value\r\n",
                "Foo[Bar: value\r\n",
            };

        for (int i = 0; i < bad.length; i++)
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.0\r\n" + bad[i] + "\r\n");

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);
            parseAll(parser, buffer);
            assertThat(bad[i], _bad, Matchers.notNullValue());
        }
    }

    @Test
    public void testHeaderTab() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Header: value\talternate\r\n" +
                "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header", _hdr[1]);
        assertEquals("value\talternate", _val[1]);
    }

    @Test
    public void testCaseSensitiveMethod() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertThat(_complianceViolation, contains(HttpComplianceSection.METHOD_CASE_SENSITIVE));
    }

    @Test
    public void testCaseSensitiveMethodLegacy() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("gEt", _methodOrVersion);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testCaseInsensitiveHeader() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0\r\n" +
                "HOST: localhost\r\n" +
                "cOnNeCtIoN: ClOsE\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testCaseInSensitiveHeaderLegacy() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0\r\n" +
                "HOST: localhost\r\n" +
                "cOnNeCtIoN: ClOsE\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("HOST", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("cOnNeCtIoN", _hdr[1]);
        assertEquals("ClOsE", _val[1]);
        assertEquals(1, _headers);
        assertThat(_complianceViolation, contains(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE, HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE, HttpComplianceSection.CASE_INSENSITIVE_FIELD_VALUE_CACHE));
    }

    @Test
    public void testSplitHeaderParse() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "XXXXSPLIT / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "\r\nZZZZ");
        buffer.position(2);
        buffer.limit(buffer.capacity() - 2);
        buffer = buffer.slice();

        for (int i = 0; i < buffer.capacity() - 4; i++)
        {
            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);

            buffer.position(2);
            buffer.limit(2 + i);

            if (!parser.parseNext(buffer))
            {
                // consumed all
                assertEquals(0, buffer.remaining());

                // parse the rest
                buffer.limit(buffer.capacity() - 2);
                parser.parseNext(buffer);
            }

            assertEquals("SPLIT", _methodOrVersion);
            assertEquals("/", _uriOrStatus);
            assertEquals("HTTP/1.0", _versionOrReason);
            assertEquals("Host", _hdr[0]);
            assertEquals("localhost", _val[0]);
            assertEquals("Header1", _hdr[1]);
            assertEquals("value1", _val[1]);
            assertEquals("Header2", _hdr[2]);
            assertEquals("value 2a", _val[2]);
            assertEquals("Header3", _hdr[3]);
            assertEquals("3", _val[3]);
            assertEquals("Header4", _hdr[4]);
            assertEquals("value4", _val[4]);
            assertEquals("Server5", _hdr[5]);
            assertEquals("notServer", _val[5]);
            assertEquals(5, _headers);
        }
    }

    @Test
    public void testChunkParse() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n" +
                    "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testBadChunkParse() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
                "GET /chunk HTTP/1.0\r\n" +
                        "Header1: value1\r\n" +
                        "Transfer-Encoding: chunked, identity\r\n" +
                        "\r\n" +
                        "a;\r\n" +
                        "0123456789\r\n" +
                        "1a\r\n" +
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                        "0\r\n" +
                        "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertThat(_bad, containsString("Bad Transfer-Encoding"));
    }

    @Test
    public void testChunkParseTrailer() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n" +
                    "Trailer: value\r\n" +
                    "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
        assertEquals(1, _trailers.size());
        HttpField trailer1 = _trailers.get(0);
        assertEquals("Trailer", trailer1.getName());
        assertEquals("value", trailer1.getValue());

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testChunkParseTrailers() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n" +
                    "Trailer: value\r\n" +
                    "Foo: bar\r\n" +
                    "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(0, _headers);
        assertEquals("Transfer-Encoding", _hdr[0]);
        assertEquals("chunked", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
        assertEquals(2, _trailers.size());
        HttpField trailer1 = _trailers.get(0);
        assertEquals("Trailer", trailer1.getName());
        assertEquals("value", trailer1.getValue());
        HttpField trailer2 = _trailers.get(1);
        assertEquals("Foo", trailer2.getName());
        assertEquals("bar", trailer2.getValue());

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testChunkParseBadTrailer() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n" +
                    "Trailer: value");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_early);
    }

    @Test
    public void testChunkParseNoTrailer() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testStartEOF() throws Exception
    {
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertTrue(_early);
        assertEquals(null, _bad);
    }

    @Test
    public void testEarlyEOF() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /uri HTTP/1.0\r\n" +
                    "Content-Length: 20\r\n" +
                    "\r\n" +
                    "0123456789");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/uri", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("0123456789", _content);

        assertTrue(_early);
    }

    @Test
    public void testChunkEarlyEOF() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789", _content);

        assertTrue(_early);
    }

    @Test
    public void testMultiParse() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Header1: value1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "a;\r\n" +
                    "0123456789\r\n" +
                    "1a\r\n" +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                    "0\r\n" +

                    "\r\n" +

                    "POST /foo HTTP/1.0\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Header2: value2\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n" +

                    "PUT /doodle HTTP/1.0\r\n" +
                    "Connection: close\r\n" +
                    "Header3: value3\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertEquals(null, _content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
        assertEquals("0123456789", _content);
    }

    @Test
    public void testMultiParseEarlyEOF() throws Exception
    {
        ByteBuffer buffer0 = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0\r\n" +
                    "Connection: Keep-Alive\r\n");

        ByteBuffer buffer1 = BufferUtil.toBuffer("Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +

                "\r\n" +

                "POST /foo HTTP/1.0\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Header2: value2\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +

                "PUT /doodle HTTP/1.0\r\n" +
                "Connection: close\r\n" + "Header3: value3\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "0123456789\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer0);
        parser.atEOF();
        parser.parseNext(buffer1);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertEquals(null, _content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
        assertEquals("0123456789", _content);
    }

    @Test
    public void testResponseParse0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 Correct\r\n" +
                    "Content-Length: 10\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(10, _content.length());
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse1() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not-Modified\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("Not-Modified", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse2() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No-Content\r\n" +
                    "Header: value\r\n" +
                    "\r\n" +

                    "HTTP/1.1 200 Correct\r\n" +
                    "Content-Length: 10\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No-Content", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.reset();
        init();

        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse3() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200\r\n" +
                    "Content-Length: 10\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse4() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 \r\n" +
                    "Content-Length: 10\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseEOFContent() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 \r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals(null, _versionOrReason);
        assertEquals(12, _content.length());
        assertEquals("0123456789\r\n", _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponse304WithContentLength() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 found\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("found", _versionOrReason);
        assertEquals(null, _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponse101WithTransferEncoding() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 switching protocols\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("101", _uriOrStatus);
        assertEquals("switching protocols", _versionOrReason);
        assertEquals(null, _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseReasonIso8859_1() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 302 déplacé temporairement\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n", StandardCharsets.ISO_8859_1);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("302", _uriOrStatus);
        assertEquals("déplacé temporairement", _versionOrReason);
    }

    @Test
    public void testSeekEOF() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "\r\n" + // extra CRLF ignored
                    "HTTP/1.1 400 OK\r\n");  // extra data causes close ??

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("OK", _versionOrReason);
        assertEquals(null, _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.close();
        parser.reset();
        parser.parseNext(buffer);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testNoURI() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testNoURI2() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET \r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testUnknownReponseVersion() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HPPT/7.7 200 OK\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testNoStatus() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testNoStatus2() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 \r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadRequestVersion() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HPPT/7.7\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());

        buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.01\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        handler = new Handler();
        parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals(null, _methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadCR() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                    "Content-Length: 0\r" +
                    "Connection: close\r" +
                    "\r");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("Bad EOL", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());

        buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r" +
                    "Content-Length: 0\r" +
                    "Connection: close\r" +
                    "\r");

        handler = new Handler();
        parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("Bad EOL", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadContentLength0() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                    "Content-Length: abc\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadContentLength1() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                    "Content-Length: 9999999999999999999999999999999999999999999999\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadContentLength2() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                    "Content-Length: 1.5\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testMultipleContentLengthWithLargerThenCorrectValue()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1\r\n" +
                    "Content-Length: 2\r\n" +
                    "Content-Length: 1\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testMultipleContentLengthWithCorrectThenLargerValue()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1\r\n" +
                    "Content-Length: 1\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testTransferEncodingChunkedThenContentLength()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Content-Length: 1\r\n" +
                    "\r\n" +
                    "1\r\n" +
                    "X\r\n" +
                    "0\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertThat(_complianceViolation, contains(HttpComplianceSection.TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @Test
    public void testContentLengthThenTransferEncodingChunked()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: 1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "1\r\n" +
                    "X\r\n" +
                    "0\r\n" +
                    "\r\n");


        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertThat(_complianceViolation, contains(HttpComplianceSection.TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @Test
    public void testHost() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: host\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("host", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testUriHost11() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.1\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @Test
    public void testUriHost10() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.0\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @Test
    public void testNoHost() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
    }

    @Test
    public void testIPHost() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: 192.168.0.1\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testIPv6Host() throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.1\r\n" +
                    "Host: [::1]\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testBadIPv6Host() throws Exception
    {
        try (StacklessLogging s = new StacklessLogging(HttpParser.class))
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.1\r\n" +
                        "Host: [::1\r\n" +
                        "Connection: close\r\n" +
                        "\r\n");

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);
            parser.parseNext(buffer);
            assertThat(_bad, containsString("Bad"));
        }
    }

    @Test
    public void testHostPort() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: myhost:8888\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("myhost", _host);
        assertEquals(8888, _port);
    }

    @Test
    public void testHostBadPort() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: myhost:testBadPort\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertThat(_bad, containsString("Bad Host"));
    }

    @Test
    public void testIPHostPort() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: 192.168.0.1:8888\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(8888, _port);
    }

    @Test
    public void testIPv6HostPort() throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host: [::1]:8888\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(8888, _port);
    }

    @Test
    public void testEmptyHostPort() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                    "Host:\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals(null, _host);
        assertEquals(null, _bad);
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testCachedField() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: www.smh.com.au\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("www.smh.com.au", parser.getFieldCache().get("Host: www.smh.com.au").getValue());
        HttpField field = _fields.get(0);

        buffer.position(0);
        parseAll(parser, buffer);
        assertTrue(field == _fields.get(0));
    }

    @Test
    public void testParseRequest() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[2]);
        assertEquals("close", _val[2]);
        assertEquals("Accept-Encoding", _hdr[3]);
        assertEquals("gzip, deflated", _val[3]);
        assertEquals("Accept", _hdr[4]);
        assertEquals("unknown", _val[4]);
    }

    @Test
    public void testHTTP2Preface() throws Exception
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "PRI * HTTP/2.0\r\n" +
                "\r\n" +
                "SM\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("PRI", _methodOrVersion);
        assertEquals("*", _uriOrStatus);
        assertEquals("HTTP/2.0", _versionOrReason);
        assertEquals(-1, _headers);
        assertEquals(null, _bad);
    }

    @BeforeEach
    public void init()
    {
        _bad = null;
        _content = null;
        _methodOrVersion = null;
        _uriOrStatus = null;
        _versionOrReason = null;
        _hdr = null;
        _val = null;
        _headers = 0;
        _headerCompleted = false;
        _messageCompleted = false;
        _complianceViolation.clear();
    }

    private String _host;
    private int _port;
    private String _bad;
    private String _content;
    private String _methodOrVersion;
    private String _uriOrStatus;
    private String _versionOrReason;
    private List<HttpField> _fields = new ArrayList<>();
    private List<HttpField> _trailers = new ArrayList<>();
    private String[] _hdr;
    private String[] _val;
    private int _headers;
    private boolean _early;
    private boolean _headerCompleted;
    private boolean _messageCompleted;
    private final List<HttpComplianceSection> _complianceViolation = new ArrayList<>();

    private class Handler implements HttpParser.RequestHandler, HttpParser.ResponseHandler, HttpParser.ComplianceHandler
    {
        @Override
        public boolean content(ByteBuffer ref)
        {
            if (_content == null)
                _content = "";
            String c = BufferUtil.toString(ref, StandardCharsets.UTF_8);
            _content = _content + c;
            ref.position(ref.limit());
            return false;
        }

        @Override
        public boolean startRequest(String method, String uri, HttpVersion version)
        {
            _fields.clear();
            _trailers.clear();
            _headers = -1;
            _hdr = new String[10];
            _val = new String[10];
            _methodOrVersion = method;
            _uriOrStatus = uri;
            _versionOrReason = version == null ? null : version.asString();
            _messageCompleted = false;
            _headerCompleted = false;
            _early = false;
            return false;
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            _fields.add(field);
            _hdr[++_headers] = field.getName();
            _val[_headers] = field.getValue();

            if (field instanceof HostPortHttpField)
            {
                HostPortHttpField hpfield = (HostPortHttpField)field;
                _host = hpfield.getHost();
                _port = hpfield.getPort();
            }
        }

        @Override
        public boolean headerComplete()
        {
            _content = null;
            _headerCompleted = true;
            return false;
        }

        @Override
        public void parsedTrailer(HttpField field)
        {
            _trailers.add(field);
        }

        @Override
        public boolean contentComplete()
        {
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            _messageCompleted = true;
            return true;
        }

        @Override
        public void badMessage(BadMessageException failure)
        {
            String reason = failure.getReason();
            _bad = reason == null ? String.valueOf(failure.getCode()) : reason;
        }

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            _fields.clear();
            _trailers.clear();
            _methodOrVersion = version.asString();
            _uriOrStatus = Integer.toString(status);
            _versionOrReason = reason;
            _headers = -1;
            _hdr = new String[10];
            _val = new String[10];
            _messageCompleted = false;
            _headerCompleted = false;
            return false;
        }

        @Override
        public void earlyEOF()
        {
            _early = true;
        }

        @Override
        public int getHeaderCacheSize()
        {
            return 4096;
        }

        @Override
        public void onComplianceViolation(HttpCompliance compliance, HttpComplianceSection violation, String reason)
        {
            _complianceViolation.add(violation);
        }
    }
}
