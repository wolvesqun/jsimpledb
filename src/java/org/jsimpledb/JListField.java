
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb;

import com.google.common.base.Converter;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;

import org.jsimpledb.change.ListFieldAdd;
import org.jsimpledb.change.ListFieldClear;
import org.jsimpledb.change.ListFieldRemove;
import org.jsimpledb.change.ListFieldReplace;
import org.jsimpledb.core.ObjId;
import org.jsimpledb.core.Transaction;
import org.jsimpledb.schema.ListSchemaField;
import org.objectweb.asm.ClassWriter;

/**
 * Represents a list field in a {@link JClass}.
 */
public class JListField extends JCollectionField {

    private static final int LIST_INDEX_ENTRY_QUERY = 1;

    JListField(String name, int storageId, JSimpleField elementField, String description, Method getter) {
        super(name, storageId, elementField, description, getter);
    }

    @Override
    public List<?> getValue(JTransaction jtx, JObject jobj) {
        if (jtx == null)
            throw new IllegalArgumentException("null jtx");
        if (jobj == null)
            throw new IllegalArgumentException("null jobj");
        return jtx.readListField(jobj, this.storageId, false);
    }

    @Override
    public <R> R visit(JFieldSwitch<R> target) {
        return target.caseJListField(this);
    }

    @Override
    ListSchemaField toSchemaItem() {
        final ListSchemaField schemaField = new ListSchemaField();
        super.initialize(schemaField);
        return schemaField;
    }

    @Override
    void outputMethods(ClassGenerator<?> generator, ClassWriter cw) {
        this.outputReadMethod(generator, cw, ClassGenerator.READ_LIST_FIELD_METHOD);
    }

    @Override
    void registerChangeListener(Transaction tx, int[] path, AllChangesListener listener) {
        tx.addListFieldChangeListener(this.storageId, path, listener);
    }

    @Override
    <T> void addChangeParameterTypes(List<TypeToken<?>> types, TypeToken<T> targetType) {
        this.addChangeParameterTypes(types, targetType, this.elementField.typeToken);
    }

    // This method exists solely to bind the generic type parameters
    @SuppressWarnings("serial")
    private <T, E> void addChangeParameterTypes(List<TypeToken<?>> types, TypeToken<T> targetType, TypeToken<E> elementType) {
        types.add(new TypeToken<ListFieldAdd<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
        types.add(new TypeToken<ListFieldClear<T>>() { }
          .where(new TypeParameter<T>() { }, targetType));
        types.add(new TypeToken<ListFieldRemove<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
        types.add(new TypeToken<ListFieldReplace<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
    }

    @Override
    @SuppressWarnings("serial")
    <T, E> void addIndexEntryReturnTypes(List<TypeToken<?>> types,
      TypeToken<T> targetType, JSimpleField subField, TypeToken<E> elementType) {
        assert subField == this.elementField;
        types.add(new TypeToken<NavigableMap<E, NavigableSet<ListIndexEntry<T>>>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
    }

    @Override
    int getIndexEntryQueryType(TypeToken<?> queryObjectType) {
        return queryObjectType.getRawType().equals(ListIndexEntry.class) ? LIST_INDEX_ENTRY_QUERY : 0;
    }

    @Override
    Method getIndexEntryQueryMethod(int queryType) {
        switch (queryType) {
        case LIST_INDEX_ENTRY_QUERY:
            return ClassGenerator.QUERY_LIST_FIELD_ENTRIES_METHOD;
        default:
            throw new UnsupportedOperationException();
        }
    }

    @Override
    ListConverter<?, ?> getConverter(JTransaction jtx) {
        final Converter<?, ?> elementConverter = this.elementField.getConverter(jtx);
        if (elementConverter == null)
            return null;
        return this.createConverter(elementConverter);
    }

    // This method exists solely to bind the generic type parameters
    private <X, Y> ListConverter<X, Y> createConverter(Converter<X, Y> elementConverter) {
        return new ListConverter<X, Y>(elementConverter);
    }

    @Override
    void copyRecurse(ObjIdSet seen, JTransaction srcTx, JTransaction dstTx,
      ObjId id, JReferenceField subField, Deque<JReferenceField> nextFields) {
        assert subField == this.elementField;
        this.copyRecurse(seen, srcTx, dstTx, srcTx.tx.readListField(id, this.storageId, false), nextFields);
    }
}

