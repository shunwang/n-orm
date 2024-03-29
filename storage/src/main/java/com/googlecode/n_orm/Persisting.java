package com.googlecode.n_orm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.googlecode.n_orm.storeapi.SimpleStore;

/**
 * This annotation must be placed on any element with persistence capabilities ;
 * such annotated classes automatically implement and offer services defined by
 * {@link PersistingElement} (e.g. activation or storage).
 * <p>
 * Such defined persisting elements must define one to N non null {@link Key}
 * attributes, each of them with a different {@link Key#order()}. If a class has
 * N keys, then it must define a key for each order from 1 to N. Keys are
 * essential to compute the identifier (see
 * {@link PersistingElement#getIdentifier()}) for persisting elements, which is
 * supposed to be unique for a persisting element (i.e. two elements with the
 * same key values are supposed to represent the same element). Such class can
 * define additional <i>properties</i>. Properties (and keys, which are
 * considered as special properties) are constrained on their type which can be:
 * <ul>
 * <li>a simple type such as int, {@link String}, {@link Double}, or other types
 * defined in {@link PersistingElement#PossiblePropertyTypes}
 * <li>a class annotated with Persisting
 * <li>a class which has only {@link Key} annotated attributes
 * <li>an array of such types (even though its usage is discouraged)
 * </ul>
 * Moreover, persisting classes may define (non final non static non transient)
 * {@link java.util.Map} or {@link java.util.Set} elements. Such elements are called <i>column
 * families</i> that can be assigned to null (only). Those column families will
 * be transformed to instances of {@link com.googlecode.n_orm.cf.ColumnFamily} ({@link com.googlecode.n_orm.cf.SetColumnFamily}
 * for {@link java.util.Set}s or {@link com.googlecode.n_orm.cf.MapColumnFamily} for {@link java.util.Map}s).
 * </p>
 * <p>
 * Keys are essential to search persisting elements. Indeed, one can efficiently
 * search for persisting elements between two elements (see
 * {@link PersistingElement#compareTo(PersistingElement)}). To perform a search,
 * you will need to state the minimum and maximum value for a given key. If the
 * latter key has {@link Key#order()} N, then, to perform the search, exact
 * values for keys with order lower than N have to be supplied. Persisting
 * elements can be searched from the data store using
 * {@link StorageManagement#findElements()}. A persisting element whose
 * identifier is known can be created using
 * {@link StorageManagement#getElement(Class, String)}. In any case, found
 * elements still have to be activated using
 * {@link PersistingElement#activate(String...)}. So far, key-based search is
 * the only supported approach to search persisting elements. It is also for the
 * more efficient way for most data store (especially distributed hashtable
 * based stores such as column-oriented databases).
 * </p>
 * <p>
 * A {@link SimpleStore} is used to store persisting elements. A persisting
 * element will be stored as a row in the {@link #table()} corresponding to the
 * class with key (i.e. its qualifier or index) given by
 * {@link PersistingElement#getIdentifier()}. Its non-key properties are stored
 * in a column family named "props" (as defined in
 * {@link PropertyManagement#PROPERTY_COLUMNFAMILY_NAME}). Keys are also stored
 * there if the annotation defined {@link #storeKeys()} as true. Column families
 * ({@link java.util.Set} and {@link java.util.Map} attributes) are merely stored in column families
 * of the data store.
 * </p>
 * <p>
 * Other (non static, non transient, non final) attributes are to be stored in
 * the data store (with no additional annotations required).
 * </p>
 * <p>
 * Moreover, the persisting element has to define either a default constructor
 * (or no constructor at all), or a constructor accepting values fr each key in
 * their defined order (which is supposed to set key values by itself...).
 * </p>
 * <p>
 * In case of inheritance, the element is stored in the corresponding
 * {@link #table()} as described above, plus in {@link #table()}s corresponding
 * to inherited persisting classes. In the tables for ancestors, rows have the
 * same key as in the table for the concrete table, plus the name of the
 * instanciated class (see {@link PersistingElement#getFullIdentifier()}).
 * Non-key properties and column families are also stored in tables for
 * superclasses unless {@link #storeAlsoInSuperClasses()} is set to true.
 * </p>
 * <p>
 * To be processed, annotated classes must be "exposed" to the aspects defined
 * in this project, you should thus compile your class either with the <a
 * href="http://www.eclipse.org/aspectj">ajc tool</a>, or use the Maven <a
 * href="http://mojo.codehaus.org/aspectj-maven-plugin/">AspectJ plugin</a>,
 * which requires at runtime a dependency to the AspectJ runtime library in any
 * case.
 * </p>
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Persisting {
	/**
	 * The name of the table where should be stored instances. If let empty
	 * (default value), the name of the class is used.
	 */
	String table() default "";

	/**
	 * The time updates to the data store should be retained before they are
	 * sent. In case this number is 0 or below, no retention is applied. This
	 * parameter is helpful when a row is quite often updated. Note that
	 * activations are not aware of non-sent updates, (e.g. an
	 * {@link PersistingElement#activate(String...)} will not see new values for
	 * at least duration indicated by this parameter).
	 * 
	 * @see com.googlecode.n_orm.cache.write.WriteRetentionStore
	 */
	long writeRetentionMs() default 0;

	/**
	 * States whether key values should be stored in the property column family.
	 * The normal case is that keys are encoded in identifier of the instances.
	 */
	boolean storeKeys() default false;

	/**
	 * States whether values (properties and column families) should also be
	 * stored in superclasses. The normal case is not as information is stored
	 * in the table for the instance's class already. This option is helpful if
	 * you plan to create queries on superclasses that should also activate
	 * properties.
	 */
	boolean storeAlsoInSuperClasses() default false;

	/**
	 * States whether this class should target more than one table.<br>
	 * When true, class automatically implements
	 * {@link PersistingElementOverFederatedTable}.<br>
	 * If true, table name is determined on a per-object basis, and the table is
	 * the table for the class (see {@link PersistingMixin#getTable(Class)}
	 * post-fixed with the result of the invocation of
	 * {@link PersistingElementOverFederatedTable#getTablePostfix()}.<br>
	 */
	FederatedMode federated() default FederatedMode.NONE;
}
