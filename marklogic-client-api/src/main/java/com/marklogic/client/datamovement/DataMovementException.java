/*
 * Copyright (c) 2022 MarkLogic Corporation
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
package com.marklogic.client.datamovement;

/**
 * The generic base exception used throughout the Data Movement SDK.  It is a
 * runtime exception to reduce the need to handle checked exceptions from which
 * an application cannot recover programmatically.
 */
public class DataMovementException extends RuntimeException {
  public DataMovementException(String message, Throwable cause) {
    super(message, cause);
  }
}
