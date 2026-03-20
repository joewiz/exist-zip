/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery.modules.zip;

import java.util.List;
import java.util.Map;

import org.exist.dom.QName;
import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDSL;
import org.exist.xquery.FunctionDef;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;

import static org.exist.xquery.FunctionDSL.functionDefs;

/**
 * Native implementation of the EXPath ZIP Module.
 *
 * @see <a href="http://expath.org/spec/zip">EXPath ZIP Module Specification</a>
 */
public class ZipModule extends AbstractInternalModule {

    public static final String NAMESPACE_URI = "http://expath.org/ns/zip";
    public static final String PREFIX = "zip";
    public static final String INCLUSION_DATE = "2026-03-20";
    public static final String RELEASED_IN_VERSION = "7.0.0";

    public static final FunctionDef[] functions = functionDefs(
            functionDefs(ZipEntryFunctions.class,
                    ZipEntryFunctions.FS_BINARY_ENTRY,
                    ZipEntryFunctions.FS_HTML_ENTRY,
                    ZipEntryFunctions.FS_TEXT_ENTRY,
                    ZipEntryFunctions.FS_TEXT_ENTRY_ENCODING,
                    ZipEntryFunctions.FS_XML_ENTRY
            ),
            functionDefs(ZipFileFunctions.class,
                    ZipFileFunctions.FS_ENTRIES,
                    ZipFileFunctions.FS_ZIP_FILE,
                    ZipFileFunctions.FS_UPDATE_ENTRIES
            )
    );

    public ZipModule(final Map<String, List<? extends Object>> parameters) {
        super(functions, parameters);
    }

    @Override
    public String getNamespaceURI() {
        return NAMESPACE_URI;
    }

    @Override
    public String getDefaultPrefix() {
        return PREFIX;
    }

    @Override
    public String getDescription() {
        return "EXPath ZIP Module — native implementation using java.util.zip";
    }

    @Override
    public String getReleaseVersion() {
        return RELEASED_IN_VERSION;
    }

    static FunctionSignature functionSignature(final String name, final String description,
            final FunctionReturnSequenceType returnType, final FunctionParameterSequenceType... paramTypes) {
        return FunctionDSL.functionSignature(new QName(name, NAMESPACE_URI, PREFIX), description, returnType, paramTypes);
    }

    static FunctionSignature[] functionSignatures(final String name, final String description,
            final FunctionReturnSequenceType returnType, final FunctionParameterSequenceType[][] variableParamTypes) {
        return FunctionDSL.functionSignatures(new QName(name, NAMESPACE_URI, PREFIX), description, returnType, variableParamTypes);
    }
}
