/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sidebeziumdemo;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.messaging.MessageHeaders;

/**
 *
 * @author Christian Tzolov
 */
public class PrintUtils {

	// Print utilities
	static ObjectMapper mapper = new ObjectMapper();

	public static String toString(Object obj) {
		return new String((byte[]) obj);
	}

	public static String prettyJson(byte[] inputJson) {
		return prettyJson(toString(inputJson));
	}

	public static String prettyJson(String inputJson) {
		try {
			return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(inputJson));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return "error";
	}

	public static String headersToString(MessageHeaders headers) {
		String result = "";
		for (String key : headers.keySet()) {
			Object value = headers.get(key);
			if (value instanceof String) {
				result = result + "|" + key + "=" + value;
			}
			else if (value instanceof byte[]) {
				result = result + "|" + key + "=" + new String((byte[]) value);
			}
			else {
				result = result + "|" + key + "=" + value;
			}
		}
		return result;
	}

}
