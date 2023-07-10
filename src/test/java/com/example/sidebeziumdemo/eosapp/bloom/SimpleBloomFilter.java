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

package com.example.sidebeziumdemo.eosapp.bloom;

import java.util.BitSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * From https://medium.com/@abhishekranjandev/demystifying-bloom-filters-with-real-life-examples-b66db7e37b37
 * BloomFilterSimple.java
 * @author Christian Tzolov
 */
public class SimpleBloomFilter {
	private BitSet bitSet;
	private int bitSetSize;
	private int numOfHashFunctions;

	public SimpleBloomFilter(int size, int numOfHashFunctions) {
		this.bitSetSize = size;
		this.numOfHashFunctions = numOfHashFunctions;
		this.bitSet = new BitSet(bitSetSize);
	}

	// Add element to Bloom Filter
	public void put(byte[] value) {
		for (int i = 0; i < numOfHashFunctions; i++) {
			int hashCode = getHash(value, i);
			bitSet.set(Math.abs(hashCode % bitSetSize));
		}
	}

	public void put(int value) {
		put(intToBytes(value));
	}

	// Check if element is present in Bloom Filter
	public boolean mightContain(byte[] value) {
		for (int i = 0; i < numOfHashFunctions; i++) {
			int hashCode = getHash(value, i);
			if (!bitSet.get(Math.abs(hashCode % bitSetSize))) {
				return false;
			}
		}
		return true;
	}

	public boolean mightContain(int value) {
		return mightContain(intToBytes(value));
	}

	// Computes the i-th hash function for the given URL
	private int getHash(byte[] value, int i) {
		try {
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			md5.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array());
			md5.update(value);
			byte[] digest = md5.digest();
			int hash = ByteBuffer.wrap(digest).getInt();
			return hash;
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}

	}

	public static byte[] intToBytes(final int i) {
		ByteBuffer bb = ByteBuffer.allocate(4);
		bb.putInt(i);
		return bb.array();
	}

	public static void main(String[] args) throws NoSuchAlgorithmException {
		SimpleBloomFilter bloomFilter = new SimpleBloomFilter(1000000, 3);

		// Add some URLs to the Bloom filter
		bloomFilter.put("http://example1.com".getBytes());
		bloomFilter.put("http://example2.com".getBytes());

		// Check if URLs are present in the Bloom filter
		System.out.println(bloomFilter.mightContain("http://example1.com".getBytes())); // Outputs: true
		System.out.println(bloomFilter.mightContain("http://example3.com".getBytes())); // Outputs: false
	}
}