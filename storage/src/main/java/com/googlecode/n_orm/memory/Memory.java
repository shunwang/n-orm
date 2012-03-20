package com.googlecode.n_orm.memory;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import com.googlecode.n_orm.DatabaseNotReachedException;
import com.googlecode.n_orm.EmptyCloseableIterator;
import com.googlecode.n_orm.conversion.ConversionTools;
import com.googlecode.n_orm.memory.Memory.Table.Row;
import com.googlecode.n_orm.memory.Memory.Table.Row.ColumnFamily;
import com.googlecode.n_orm.memory.Memory.Table.Row.ColumnFamily.ByteValue;
import com.googlecode.n_orm.memory.Memory.Table.Row.ColumnFamily.Value;
import com.googlecode.n_orm.storeapi.CloseableKeyIterator;
import com.googlecode.n_orm.storeapi.Constraint;
import com.googlecode.n_orm.storeapi.SimpleStore;

/**
 * Reference implementation for a store based on {@link ConcurrentSkipListMap}.
 * This store entirely resides into memory, and is only available for the current JVM.
 * It is well suited for testing.
 * This store is thread-safe.
 * This store does not supports mixing incrementing and absolute values.
 */
public class Memory implements SimpleStore {
	public static final Memory INSTANCE = new Memory();
	
	/**
	 * Utility method used internally to find a sub-map with inclusive nullable start and stop keys.
	 * @param map the map to be searched-in
	 * @param fromIncl the start key ; can be null to state the start of the map
	 * @param toIncl the last key ; can be null to state the end of the map
	 * @return a sub-map (can be map or a view on map)
	 */
	protected static <T> NavigableMap<String, T> subMap(NavigableMap<String, T> map, String fromIncl, String toIncl) {
		switch ((fromIncl == null ? 0 : 2) + (toIncl == null ? 0 : 1)) {
		case 0: //no filter
			return map;
		case 1: //toIncl only
			return map.headMap(toIncl, true);
		case 2: //fromIncl only
			return map.tailMap(fromIncl, true);
		case 3: //two keys
			return map.subMap(fromIncl, true, toIncl, true);
		default:
			assert false;
			return map;
		}
		
	}

	/**
	 * A map that creates necessary keys as soon as they are requested by {@link #get(String)}.
	 * Use {@link #getNoCreate(String)} to avoid creating a new element lazily.
	 * Keys are all {@link String}.
	 * @param <T> the kind of elements owned by the map.
	 */
	private abstract class LazyMap<T> {
		
		/**
		 * The actual map owning elements.
		 */
		protected ConcurrentNavigableMap<String, T> map = new ConcurrentSkipListMap<String,T>();
		
		/**
		 * Called by {@link #get(String)} when no value is found
		 * @param key the key for the newly created element
		 * @return the created element that is not in this map yet
		 */
		protected abstract T newElement(String key);

		/**
		 * Whether this key is known by this map
		 * @param key the key to search (cannot be null)
		 */
		public boolean contains(String key) {
			if (key == null)
				throw new NullPointerException();
			return map.containsKey(key);
		}

		/**
		 * Returns the value to the element stored with a given key.
		 * In case this element does not exists, a new one is created using {@link #newElement(String)}, stored in the map and returned.
		 * @param key the non-null key of the (possibly new) element
		 * @return the (possibly newly created) element stored under key key
		 */
		public T get(String key) {
			if (key == null)
				throw new NullPointerException();
			T ret = this.getNoCreate(key);
			if (ret == null) {
				T newT = this.newElement(key);
				T oldT = this.map.putIfAbsent(key, newT);
				ret = oldT == null ? newT : oldT;
			}
			return ret;
		}
		
		/**
		 * Returns the value to the element stored with a given key.
		 * @param key the non-null key of the (not new) element
		 * In case this element does not exists, returns null.
		 */
		protected T getNoCreate(String key) {
			if (key == null)
				throw new NullPointerException();
			return map.get(key);
		}

		/**
		 * Puts an element in the map.
		 * @param key the non-null key of the element
		 * @param value must not be null; use {@link #remove(String)} to remove an element
		 */
		public T put(String key, T value) {
			if (key == null)
				throw new NullPointerException();
			if (value == null)
				throw new NullPointerException();
			return map.put(key, value);
		}


		/**
		 * Removes an element from the map.
		 * @param key the non-null key of the element
		 * @param value must not be null; use {@link #remove(String)} to remove an element
		 * @return the previous value for the map
		 */
		public  T remove(String key) {
			if (key == null)
				throw new NullPointerException();
			return map.remove(key);
		}


		/**
		 * Removes elements from the map according to their keys.
		 * @param key the non-null key of the element
		 * @param value must not be null; use {@link #remove(String)} to remove an element
		 */
		public void removeAll(Set<String> keys) {
			if (keys == null)
				throw new NullPointerException();
			map.keySet().removeAll(keys);
		}

		/**
		 * Vacuums this map.
		 */
		public void clear() {
			this.map.clear();
		}
	}

	/**
	 * An map to store rows within tables.
	 * Rows are indexed according to their keys.
	 * Rows are sorted within a table according to their key value so that rage search can be fast (see {@link #toMap(String, String)}.
	 */
	public class Table extends LazyMap<Table.Row> {
		
		/**
		 * The name for this table
		 */
		public final String name;
		
		public Table(String name) {
			this.name = name;
		}

		/**
		 * Creates a new {@link Row} with the given key
		 */
		protected Row newElement(String key) {
			return new Row(key);
		}
		
		/**
		 * Creates an {@link Iterator} over the {@link Row}s owned by this table starting from row with the given qualifier.
		 * @param fromKeyIncl the qualifier of the first row ; if a row with this qualifier does not exist, takes the row with the lowest key greater than fromKeyIncl
		 * @see Memory#subMap(NavigableMap, String, String)
		 */
		public Iterator<Row> getRowIterator(String fromKeyIncl) {
			return subMap(this.map, fromKeyIncl, null).values().iterator();
		}
		
		/**
		 * A row owning a set of Column families.
		 * Column families are created lazily as soon as they are requested by {@link #get(String)}.
		 */
		public class Row extends LazyMap<Row.ColumnFamily> implements com.googlecode.n_orm.storeapi.Row {
			/**
			 * The identifier for this row
			 */
			public final String key;
			
			public Row(String key) {
				this.key = key;
			}

			/**
			 * Creates a column family according to its name.
			 */
			@Override
			protected ColumnFamily newElement(String name) {
				return new ColumnFamily(name);
			}

			/**
			 * The key for this row.
			 */
			@Override
			public String getKey() {
				return key;
			}

			@Override
			public Map<String, Map<String, byte[]>> getValues() {
				Map<String, Map<String, byte[]>> ret = new TreeMap<String, Map<String,byte[]>>();
				for (Entry<String, ColumnFamily> element : map.entrySet()) {
					ret.put(element.getKey(), element.getValue().getValues(null, null));
				}
				return ret;
			}

			/**
			 * A column family owning values.
			 * Values are sorted according to their qualifier so that rage search can be fast (see {@link #toMap(String, String)}.
			 */
			public class ColumnFamily extends LazyMap<ColumnFamily.Value<?>> {
				/**
				 * The name for this column family
				 */
				public final String name;
				
				public ColumnFamily(String name) {
					this.name = name;
				}

				/**
				 * Creates a byte value
				 */
				@Override
				protected Value<?> newElement(String qualifier) {
					return new ByteValue(qualifier, new byte [0]);
				}

				/**
				 * Increments the value at the given key.
				 * @param qualifier the qualifier of the incremented value
				 * @param increment the non-null increment to be applied
				 */
				public void incr(String qualifier, Number increment) {
					assert increment.longValue() != 0 : "Received a 0 increment for table" + Table.this.name + ", row " + Row.this.key + ", family " + this.name + ", qualifier " + key; 
					IncrementingValue newVal = new IncrementingValue(qualifier, increment.longValue());
					Value<?> oldVal = this.map.putIfAbsent(qualifier, newVal);
					if (oldVal == null)
						return;
					else if (! (oldVal instanceof IncrementingValue))
						throw new IllegalStateException("Cannot increment a byte array value (value for " + qualifier + " in family " + this.name + " fro row " + Row.this.key + " in table " + Table.this.name + " is already set a non incrementing way");
					((IncrementingValue)oldVal).increment(increment.longValue());
				}
				
				/**
				 * The set of values in this column family mapped according to their qualifier.
				 * Qualifiers must be included between fromQualifierIncl and toQualifierIcl
				 */
				public Map<String, byte[]> getValues(String fromQualifierIncl, String toQualifierIcl) {
					Map<String, byte[]> ret = new TreeMap<String,byte[]>();
					for (Entry<String, Value<?>> element : subMap(map, fromQualifierIncl, toQualifierIcl).entrySet()) {
						ret.put(element.getKey(), element.getValue().getBytes());
					}
					return ret;
				}
				
				public abstract class Value<T> {
					/**
					 * The qualifier for this value
					 */
					public final String qualifier;
					
					/**
					 * Creates a value that cannot be incremented
					 * @param qualifier
					 * @param value
					 */
					public Value(String qualifier) {
						this.qualifier = qualifier;
					}
					
					/**
					 * The byte array representation for this value.
					 */
					public abstract byte[] getBytes();
				}
				
				public class ByteValue extends Value<byte[]> {
					
					/**
					 * The actual value for this value
					 */
					protected byte[] value;

					public ByteValue(String qualifier, byte[] value) {
						super(qualifier);
						this.value = value;
					}
					
					public byte[] getValue() {
						return this.value;
					}
					
					public void setValue(byte[] value) {
						this.value = value;
					}

					@Override
					public byte[] getBytes() {
						return this.value;
					}
				}
				
				public class IncrementingValue extends Value<Long> {
					
					/**
					 * The actual value for this value
					 */
					protected final AtomicLong value;

					public IncrementingValue(String qualifier, Long value) {
						super(qualifier);
						this.value = new AtomicLong(value);
					}
					
					public long getValue() {
						return this.value.get();
					}
					
					/**
					 * Atomically increments this value.
					 * @param increment can be negatve or positive
					 */
					public void increment(long increment) {
						this.value.getAndAdd(increment);
					}
					
					@Override
					public byte[] getBytes() {
						return ConversionTools.convert(this.value.get());
					}
				}
			}

		}
		
	}
	
	/**
	 * The set of tables in this store
	 */
	private LazyMap<Table> tables = new LazyMap<Table>() {

		/**
		 * Creates a table according to its name
		 */
		@Override
		protected Table newElement(String name) {
			return new Table(name);
		}
		
	};
	
	private Memory() {}

	@Override
	public void start() {
	}
	
	/**
	 * Returns the in-memory table.
	 * @param table the name of the expected table
	 * @param createIfNecessary whether to create the table if it does not exist
	 * @return the table ; can be null if createIfNecessary is set to false and the table does not exist
	 */
	public Table getTable(String table, boolean createIfNecessary) {
		return createIfNecessary ? this.tables.get(table) : this.tables.getNoCreate(table);
	}
	
	/**
	 * Returns the in-memory row (compatible with {@link com.googlecode.n_orm.storeapi.Row}) from a table.
	 * @param table the name of the table in which to find the row
	 * @param id the unique identifier for the row within the table
	 * @param createIfNecessary whether to create the row if it does not exist (including in case table does not exists)
	 * @return the row ; can be null if createIfNecessary is set to false and the row or the table does not exist
	 */
	public Row getRow(String table, String id, boolean createIfNecessary) {
		Table t = this.getTable(table, createIfNecessary);
		return createIfNecessary ? t.get(id) : (t == null ? null : t.getNoCreate(id));
	}

	/**
	 * Returns the in-memory column family for a given row from a table.
	 * @param table the name of the table in which to find the column family for the row
	 * @param id the unique identifier for the row within the table
	 * @param family the name of the column family to be found within the row
	 * @param createIfNecessary whether to create the row if it does not exist (including in case table or row does not exists)
	 * @return the row ; can be null if createIfNecessary is set to false and the column family or the row or the table does not exist
	 */
	public ColumnFamily getFamily(String table, String id, String family, boolean createIfNecessary) {
		Row r = this.getRow(table, id, createIfNecessary);
		return createIfNecessary ? r.get(family) : r == null ? null : r.getNoCreate(family);
	}

	@Override
	public byte[] get(String table, String id, String family, String qualifer) {
		ColumnFamily fam = this.getFamily(table, id, family, false);
		Value<?> val = fam == null ? null : fam.getNoCreate(qualifer);
		return val == null ? null : val.getBytes();
	}

	@Override
	public Map<String, byte[]> get(String table, String id, String family) {
		ColumnFamily fam = this.getFamily(table, id, family, false);
		return fam == null ? null : fam.getValues(null, null);
	}
	
	@Override
	public Map<String, byte[]> get(String table, String id, String family,
			Constraint c) throws DatabaseNotReachedException {
		ColumnFamily fam = this.getFamily(table, id, family, false);
		return fam == null ? null : fam.getValues(c == null ? null : c.getStartKey(), c == null ? null : c.getEndKey());
	}

	@Override
	public void storeChanges(String table, String id,
			Map<String, Map<String, byte[]>> changed,
			Map<String, Set<String>> removed,
			Map<String, Map<String, Number>> incremented) {
		IllegalArgumentException x = null;
		
		Row r = this.getRow(table, id, true);
		
		for (Entry<String, Map<String, byte[]>> change : changed.entrySet()) {
			ColumnFamily f = r.get(change.getKey());
			for (Entry<String, byte[]> value : change.getValue().entrySet()) {
				Value<?> val = f.get(value.getKey());
				if (val instanceof ByteValue) {
					((ByteValue)val).setValue(value.getValue());
				} else {
					x = new IllegalArgumentException("Cannot set an incrementing value " + value.getKey() + " in family " + change.getKey() + " for row " + id + " in table " + table);
				}
			}
		}
		
		for (Entry<String, Set<String>> remove : removed.entrySet()) {
			r.get(remove.getKey()).removeAll(remove.getValue());
		}
		
		for (Entry<String, Map<String, Number>> incr : incremented.entrySet()) {
			ColumnFamily f = r.get(incr.getKey());
			for (Entry<String, Number> entry : incr.getValue().entrySet()) {
				f.incr(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public long count(String table, Constraint c)
			throws DatabaseNotReachedException {
		Table t = this.getTable(table, false);
		return t == null ? 0 : subMap(t.map, c == null ? null : c.getStartKey(), c == null ? null : c.getEndKey()).size();
	}
	
	public void reset() {
		this.tables.clear();
	}

	@Override
	public void delete(String table, String id) {
		Table t = this.getTable(table, false);
		if (t != null)
			t.remove(id);
	}

	@Override
	public boolean exists(String table, String row, String family)
			throws DatabaseNotReachedException {
		return this.getFamily(table, row, family, false) != null;
	}

	@Override
	public boolean exists(String table, String row)
			throws DatabaseNotReachedException {
		if (!this.tables.contains(table))
			return false;
		return this.getRow(table, row, false) != null;
	}

	@Override
	public Map<String, Map<String, byte[]>> get(String table, String id, Set<String> families) throws DatabaseNotReachedException {
		Row row = this.getRow(table, id, false);
		if (row == null)
			return null;
		
		Map<String, Map<String, byte[]>> ret = new TreeMap<String, Map<String,byte[]>>();
		
		for (String family : families) {
			ColumnFamily fam = row.getNoCreate(family);
			if (fam != null) {
				ret.put(family, fam.getValues(null, null));
			}
		}
		
		return ret;
	}

	@Override
	public CloseableKeyIterator get(final String table, Constraint c, final int limit, Set<String> families)
			throws DatabaseNotReachedException {
		Table t = this.getTable(table, false);
		if (t == null)
			return new EmptyCloseableIterator();
		final String endKey = c == null ? null : c.getEndKey();
		final Iterator<Row> ret = t.getRowIterator(c == null ? null : c.getStartKey());
		return new CloseableKeyIterator() {
			private int count = 0;
			private Row next = null;
			private boolean done;
			
			private Row updateNext() {
				if (next != null)
					return next;
				
				if (done) {
					return next = null;
				}
				
				if (++count > limit) {
					done = true;
					return next = null;
				}
				
				next = ret.hasNext() ? ret.next() : null;
				
				if (next == null
						|| (endKey != null && next.getKey().compareTo(endKey) > 0) ) {
					done = true;
					next = null;
				}
					
				return next;
			}	
			
			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
			
			@Override
			public com.googlecode.n_orm.storeapi.Row next() {
				com.googlecode.n_orm.storeapi.Row ret = updateNext();
				next = null;
				return ret;
			}
			
			@Override
			public boolean hasNext() {
				return this.updateNext() != null;
			}
			
			@Override
			public void close() {
			}
		}; 
	}

}
