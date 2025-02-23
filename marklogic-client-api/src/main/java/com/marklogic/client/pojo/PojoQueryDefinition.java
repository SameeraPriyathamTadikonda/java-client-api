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
package com.marklogic.client.pojo;

import com.marklogic.client.query.QueryDefinition;

/**
 * A marker interface identifying QueryDefinition types compatible with 
 * {@link PojoRepository#search(PojoQueryDefinition, long) PojoRepository.search}
 * @see PojoRepository#search(PojoQueryDefinition, long)
 * @see PojoRepository#search(PojoQueryDefinition, long, Transaction)
 * @see PojoRepository#search(PojoQueryDefinition, long, SearchReadHandle)
 * @see PojoRepository#search(PojoQueryDefinition, long, SearchReadHandle, Transaction)
 */
public interface PojoQueryDefinition extends QueryDefinition {}
