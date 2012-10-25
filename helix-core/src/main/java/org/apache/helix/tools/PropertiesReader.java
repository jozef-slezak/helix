package org.apache.helix.tools;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.InputStream;
import java.util.Properties;

import org.apache.log4j.Logger;

public class PropertiesReader
{
  private static final Logger LOG = Logger
      .getLogger(PropertiesReader.class.getName());

  private final Properties _properties = new Properties();

  public PropertiesReader(String propertyFileName)
  {
    try
    {
      InputStream stream = Thread.currentThread().getContextClassLoader()
          .getResourceAsStream(propertyFileName);
      _properties.load(stream);
    }
    catch (Exception e)
    {
      String errMsg = "could not open properties file:" + propertyFileName;
      // LOG.error(errMsg, e);
      throw new IllegalArgumentException(errMsg, e);
    }
  }

  public String getProperty(String key)
  {
    String value = _properties.getProperty(key);
    if (value == null)
    {
      throw new IllegalArgumentException("no property exist for key:" + key);
    }

    return value;
  }
}
