/*
 * Copyright (c) 2023 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.marklogic.client.test.datamovement;

import com.marklogic.client.datamovement.NodeOperation;
import com.marklogic.client.datamovement.XMLSplitter;
import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.io.marker.XMLWriteHandle;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class XMLSplitterTest {
    static final private String xmlFile = "src/test/resources/data" + File.separator + "pathSplitter/people.xml";
    static final private String[] expected = new String[]{
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><person xmlns=\"http://www.marklogic.com/people/\" president=\"yes\"><first>George</first><last>Washington</last></person>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><person xmlns=\"http://www.marklogic.com/people/\" president=\"no\"><first>Betsy</first><last>Ross</last></person>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><person xmlns=\"http://www.marklogic.com/people/\" president=\"yes\"><first>John</first><last>Kennedy</last></person>"
    };

    @Test
    public void testXMLSplitter() throws Exception {

        XMLSplitter splitter = XMLSplitter.makeSplitter("http://www.marklogic.com/people/", "person");
        FileInputStream fileInputStream = new FileInputStream(new File(xmlFile));
        Stream<StringHandle> contentStream = splitter.split(fileInputStream);
        assertNotNull(contentStream);

        StringHandle[] result = contentStream.toArray(size -> new StringHandle[size]);
        assertEquals(3, splitter.getCount());
        assertNotNull(result);

        for (int i = 0; i < result.length; i++) {
            String element = result[i].get();
            assertNotNull(element);
            assertEquals(expected[i], element);
        }
    }

    @Test
    public void testXMLSplitterAttr() throws Exception {

        FileInputStream fileInputStream = new FileInputStream(new File(xmlFile));
        AttributeVisitor visitor = new AttributeVisitor("http://www.marklogic.com/people/",
                "person",
                "president",
                "yes");

        XMLSplitter splitter = new XMLSplitter(visitor);

        Stream<StringHandle> contentStream = splitter.split(fileInputStream);
        assertNotNull(contentStream);

        StringHandle[] result = contentStream.toArray(size -> new StringHandle[size]);
        assertEquals(1, splitter.getCount());
        assertNotNull(result);

        for (int i = 0; i < result.length; i++) {
            String element = result[i].get();
            assertNotNull(element);
            assertEquals(expected[0], element);
        }
    }

    @Test
    public void testSplitterWrite() throws Exception {

        String[] expectedURIs = {"/SystemPath/NewPeople1_abcd.xml"};
        FileInputStream fileInputStream = new FileInputStream(new File(xmlFile));

        AttributeVisitor visitor = new AttributeVisitor("http://www.marklogic.com/people/",
                "person",
                "president",
                "no");

        XMLSplitter splitter = new XMLSplitter(visitor);
        XMLSplitter.UriMaker uriMaker = new UriMakerTest();
        uriMaker.setInputAfter("/SystemPath/");
        uriMaker.setSplitFilename("NewPeople");
        splitter.setUriMaker(uriMaker);
        Stream<DocumentWriteOperation> contentStream = splitter.splitWriteOperations(fileInputStream);
        assertNotNull(contentStream);

        Iterator<DocumentWriteOperation> itr = contentStream.iterator();
        int i = 0;
        while (itr.hasNext()) {
            DocumentWriteOperation docOp = itr.next();
            String uri = docOp.getUri();
            assertEquals(docOp.getUri(), expectedURIs[i]);

            assertNotNull(docOp.getContent());
            String docOpContent = docOp.getContent().toString();
            assertEquals(docOpContent, expected[1]);
            i++;
        }
        assertEquals(1, splitter.getCount());

    }

    private class UriMakerTest implements XMLSplitter.UriMaker {
        private String inputAfter;
        private String inputName;

        @Override
        public String makeUri(long num, XMLWriteHandle handle) {
            StringBuilder uri = new StringBuilder();
            String randomUUIDForTest = "abcd";

            if (getInputAfter() != null && getInputAfter().length() != 0) {
                uri.append(getInputAfter());
            }

            if (getSplitFilename() != null && getSplitFilename().length() != 0) {
                uri.append(getSplitFilename());
            }

            uri.append(num).append("_").append(randomUUIDForTest).append(".xml");
            return uri.toString();
        }

        @Override
        public String getInputAfter() {
            return this.inputAfter;
        }

        @Override
        public void setInputAfter(String base) {
            this.inputAfter = base;
        }

        @Override
        public String getSplitFilename() {
            return this.inputName;
        }

        @Override
        public void setSplitFilename(String name) {
            this.inputName = name;
        }
    }

    @Test
    public void testSplitterWriteWithoutInputName() throws Exception {

        FileInputStream fileInputStream = new FileInputStream(new File(xmlFile));

        AttributeVisitor visitor = new AttributeVisitor("http://www.marklogic.com/people/",
                "person",
                "president",
                "no");

        XMLSplitter splitter = new XMLSplitter(visitor);

        Stream<DocumentWriteOperation> contentStream = splitter.splitWriteOperations(fileInputStream);
        assertNotNull(contentStream);

        Iterator<DocumentWriteOperation> itr = contentStream.iterator();
        while (itr.hasNext()) {
            DocumentWriteOperation docOp = itr.next();
            String uri = docOp.getUri();
            assertNotNull(docOp.getUri());
            assertTrue(docOp.getUri().startsWith("/1"));
            assertTrue(docOp.getUri().endsWith(".xml"));

            assertNotNull(docOp.getContent());
            String docOpContent = docOp.getContent().toString();
            assertEquals(docOpContent, expected[1]);
        }
        assertEquals(1, splitter.getCount());

    }

    @Test
    public void testSplitterWriteWithInputName() throws Exception {

        FileInputStream fileInputStream = new FileInputStream(new File(xmlFile));

        AttributeVisitor visitor = new AttributeVisitor("http://www.marklogic.com/people/",
                "person",
                "president",
                "no");

        XMLSplitter splitter = new XMLSplitter(visitor);

        Stream<DocumentWriteOperation> contentStream = splitter.splitWriteOperations(fileInputStream, "TestSplitter.xml");
        assertNotNull(contentStream);

        Iterator<DocumentWriteOperation> itr = contentStream.iterator();
        while (itr.hasNext()) {
            DocumentWriteOperation docOp = itr.next();
            String uri = docOp.getUri();
            assertNotNull(docOp.getUri());
            assertTrue(docOp.getUri().startsWith("TestSplitter1"));
            assertTrue(docOp.getUri().endsWith(".xml"));

            assertNotNull(docOp.getContent());
            String docOpContent = docOp.getContent().toString();
            assertEquals(docOpContent, expected[1]);
        }
        assertEquals(1, splitter.getCount());

    }

    static public class AttributeVisitor extends XMLSplitter.Visitor<StringHandle> {
        private String nsUri, localName, attrName, attrValue;

        public AttributeVisitor(String nsUri,
                                String localName,
                                String attrName,
                                String attrValue) {
            this.nsUri = nsUri;
            this.localName = localName;
            this.attrName = attrName;
            this.attrValue = attrValue;
        }

        @Override
        public NodeOperation startElement(XMLSplitter.StartElementReader startElementReader) {
            if (startElementReader.getNamespaceURI().equals(this.nsUri) &&
                startElementReader.getLocalName().equals(this.localName)) {

                int i;
                for (i = 0; i < startElementReader.getAttributeCount(); i++) {
                    String localAttrName = startElementReader.getAttributeLocalName(i);
                    String localAttrValue = startElementReader.getAttributeValue(i);

                    if (localAttrName.equals(this.attrName) && localAttrValue.equals(this.attrValue)) {
                        return NodeOperation.PROCESS;
                    }
                }
            }

            if (startElementReader.getNamespaceURI().equals(this.nsUri) &&
                    startElementReader.getLocalName().equals("skip")) {
                return NodeOperation.SKIP;
            }

            return NodeOperation.DESCEND;
        }

        @Override
        public StringHandle makeBufferedHandle(XMLStreamReader elementBranchReader) {
            String content = serialize(elementBranchReader);
            return new StringHandle(content).withFormat(Format.XML);
        }

    }

}
