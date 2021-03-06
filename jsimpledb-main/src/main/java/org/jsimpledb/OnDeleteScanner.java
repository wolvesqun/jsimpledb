
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb;

import java.lang.reflect.Method;

import org.jsimpledb.annotation.OnDelete;

/**
 * Scans for {@link OnDelete &#64;OnDelete} annotations.
 */
class OnDeleteScanner<T> extends AnnotationScanner<T, OnDelete> {

    OnDeleteScanner(JClass<T> jclass) {
        super(jclass, OnDelete.class);
    }

    @Override
    protected boolean includeMethod(Method method, OnDelete annotation) {
        this.checkNotStatic(method);
        this.checkReturnType(method, void.class);
        this.checkParameterTypes(method);
        return true;
    }
}

