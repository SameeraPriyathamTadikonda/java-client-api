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
package com.marklogic.client.test;

import com.marklogic.client.impl.HandleAccessor;
import com.marklogic.client.io.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
public class HandleAccessorTest {
  public static boolean fileInputStreamWasClosed;

  @Test
  public void testContentAsString() throws URISyntaxException, IOException {
    // I'm purposely using a string with a non-ascii character to test
    // charset issues
    String hola = "¡Hola!";
    System.out.println("Default Java Charset: " + Charset.defaultCharset());
    assertEquals( hola,
      HandleAccessor.contentAsString(new StringHandle(hola)));
    assertEquals(hola,
      HandleAccessor.contentAsString(new BytesHandle(hola.getBytes("UTF-8"))));
    URL filePath = this.getClass().getClassLoader().getResource("hola.txt");
    assertEquals( hola,
      HandleAccessor.contentAsString(new ReaderHandle(new StringReader(hola))));
    assertEquals( hola,
      HandleAccessor.contentAsString(new FileHandle(new File(filePath.toURI()))));
    assertEquals( hola,
      HandleAccessor.contentAsString(new InputStreamHandle(filePath.openStream())));

    InputStream fileInputStream = new FileInputStream(new File(filePath.toURI())) {
      @Override
      public void close() throws IOException {
          super.close();
          HandleAccessorTest.fileInputStreamWasClosed = true;
      }
    };
    assertEquals( hola,
      HandleAccessor.contentAsString(new InputStreamHandle(fileInputStream)));
    assertTrue(this.fileInputStreamWasClosed);
  }
}
