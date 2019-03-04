/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package com.johnsoft.tools;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DCONST_0;
import static org.objectweb.asm.Opcodes.DRETURN;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.FCONST_0;
import static org.objectweb.asm.Opcodes.FRETURN;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.LCONST_0;
import static org.objectweb.asm.Opcodes.LRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.RETURN;
import static com.johnsoft.tools.NoDepsCompiler.Class.TypeType.INTERFACE;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Create class file with class/field/method description, without java source code and dependencies
 *
 * Generics/Annotations/Enums are not supported at this time
 *
 * Gradle: implementation 'org.ow2.asm:asm:7.0'
 *
 * @author John Kenrinus Lee
 * @version 2019-02-28
 */
@SuppressWarnings({ "unused", "WeakerAccess" })
public class NoDepsCompiler {
    private final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    private final int level;
    private final int synthetic;

    public NoDepsCompiler(int version, boolean markSynthetic) {
        switch (version) {
            case 6:
                level = Opcodes.V1_6;
                break;
            case 7:
                level = Opcodes.V1_7;
                break;
            default:
                level = Opcodes.V1_8;
                break;
        }
        if (markSynthetic) {
            synthetic = ACC_SYNTHETIC;
        } else {
            synthetic = 0;
        }
    }

    public void createClassToPath(Class klass, String path, boolean makeThrow) {
        beginClass(klass);
        for (Field field : klass.getFields()) {
            addField(field);
        }
        for (Class innerClass : klass.getInnerClasses()) {
            addInnerClass(innerClass);
        }
        for (Method method : klass.getMethods()) {
            if (makeThrow) {
                addMethodWithThrow(method);
            } else {
                addMethodWithDefault(method);
            }
        }
        endToFile(path);
    }

    public void beginClass(Class klass) {
        int access = klass.getAccess() | synthetic;
        access |= ACC_SUPER;
        access &= ~ACC_STATIC;
        access &= ~ACC_PRIVATE;
        access &= ~ACC_PROTECTED;
        cw.visit(level, access, klass.mName, null, klass.mSuper, klass.mInterfaces);
    }

    public void addInnerClass(Class innerClass) {
        String defineClass = innerClass.mName;
        String[] classes = defineClass.split("\\$");
        cw.visitInnerClass(defineClass, classes[0], classes[1], innerClass.getAccess() | synthetic);
    }

    public void addField(Field field) {
        cw.visitField(field.getAccess() | synthetic, field.mName, field.mType, null, field.mValue);
    }

    public void addMethodWithThrow(Method method) {
        MethodVisitor methodVisitor = cw.visitMethod(method.getAccess() | synthetic, method.mName,
                method.getDescriptor(), null, method.mExceptionTypes);
        methodVisitor.visitCode();
        makeDefaultThrowStatement(methodVisitor);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    public void addMethodWithDefault(Method method) {
        MethodVisitor methodVisitor = cw.visitMethod(method.getAccess() | synthetic, method.mName,
                method.getDescriptor(), null, method.mExceptionTypes);
        methodVisitor.visitCode();
        makeDefaultReturnStatement(methodVisitor, method.mReturnType);
        methodVisitor.visitMaxs(0, 0);
        methodVisitor.visitEnd();
    }

    public void endToFile(String path) {
        cw.visitEnd();
        byte[] data = cw.toByteArray();
        File file = new File(path);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void makeDefaultThrowStatement(MethodVisitor methodVisitor) {
        String exceptionType = "java/lang/UnsupportedOperationException";
        methodVisitor.visitTypeInsn(NEW, exceptionType);
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitLdcInsn("Not implemented!");
        methodVisitor.visitMethodInsn(INVOKESPECIAL, exceptionType, "<init>", "(Ljava/lang/String;)V", false);
        methodVisitor.visitInsn(ATHROW);
    }

    private void makeDefaultReturnStatement(MethodVisitor methodVisitor, String returnType) {
        switch (returnType) {
            case "V":
                methodVisitor.visitInsn(RETURN);
            case "I":
            case "C":
            case "S":
            case "B":
            case "Z":
                methodVisitor.visitInsn(ICONST_0);
                methodVisitor.visitInsn(IRETURN);
            case "L":
                methodVisitor.visitInsn(LCONST_0);
                methodVisitor.visitInsn(LRETURN);
            case "F":
                methodVisitor.visitInsn(FCONST_0);
                methodVisitor.visitInsn(FRETURN);
            case "D":
                methodVisitor.visitInsn(DCONST_0);
                methodVisitor.visitInsn(DRETURN);
            default:
                methodVisitor.visitInsn(ACONST_NULL);
                methodVisitor.visitInsn(ARETURN);
        }
    }

    private static final class Signature {
        static String getType(String type) {
            String result;
            type = type.replace("[]", "[");
            final int idx;
            String prefix = "";
            if ((idx = type.indexOf("[")) > 0) {
                prefix = type.substring(idx);
                type = type.substring(0, idx);
            }
            switch (type) {
                case "void":
                    result = "V";
                    break;
                case "boolean":
                    result = prefix + "Z";
                    break;
                case "byte":
                    result = prefix + "B";
                    break;
                case "char":
                    result = prefix + "C";
                    break;
                case "short":
                    result = prefix + "S";
                    break;
                case "int":
                    result = prefix + "I";
                    break;
                case "long":
                    result = prefix + "J";
                    break;
                case "float":
                    result = prefix + "F";
                    break;
                case "double":
                    result = prefix + "D";
                    break;
                default:
                    result = prefix + "L" + type.replace('.', '/') + ";";
                    break;
            }
            return result;
        }
    }

    public static class UserDataHolder {
        private final HashMap<String, Object> userMap = new HashMap<>();

        public boolean putUserData(String key, Object value) {
            return userMap.put(key, value) != null;
        }

        public Object getUserData(String key, Object defaultValue) {
            final Object v;
            return (((v = userMap.get(key)) != null) || userMap.containsKey(key)) ? v : defaultValue;
        }
    }

    @SuppressWarnings({ "unchecked", "UnusedReturnValue" })
    public static class Access<T extends Access<T>> extends UserDataHolder {
        boolean mPublic;
        boolean mPrivate;
        boolean mProtected;
        boolean mStatic;
        boolean mFinal;

        public T setStatic() {
            mStatic = true;
            return (T) this;
        }

        public T setFinal() {
            mFinal = true;
            return (T) this;
        }

        public T setPublic() {
            mPublic = true;
            mPrivate = false;
            mProtected = false;
            return (T) this;
        }

        public T setPrivate() {
            mPrivate = true;
            mProtected = false;
            mPublic = false;
            return (T) this;
        }

        public T setProtected() {
            mProtected = true;
            mPrivate = false;
            mPublic = false;
            return (T) this;
        }

        int getAccess() {
            int access = 0;
            if (mPublic) {
                access |= ACC_PUBLIC;
            } else if (mPrivate) {
                access |= ACC_PRIVATE;
            } else if (mProtected) {
                access = ACC_PROTECTED;
            }
            if (mStatic) {
                access |= ACC_STATIC;
            }
            if (mFinal) {
                access |= ACC_FINAL;
            }
            return access;
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static class Field extends Access<Field> {
        String mType;
        String mName;
        Object mValue;

        public Field setName(String name) {
            mName = name;
            return this;
        }

        public Field setValue(Object value) {
            mValue = value;
            return this;
        }

        public Field setType(String type) {
            mType = Signature.getType(type);
            return this;
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static class Method extends Access<Method> {
        boolean mAbstract;
        boolean mNative;
        String mName;
        String mReturnType;
        String[] mParameterTypes = new String[0];
        String[] mExceptionTypes = new String[0];

        String getDescriptor() {
            StringBuilder descriptor = new StringBuilder("(");
            for (String type : mParameterTypes) {
                descriptor.append(type);
            }
            descriptor.append(")").append(mReturnType);
            return descriptor.toString();
        }

        @Override
        int getAccess() {
            int access = super.getAccess();
            if (mAbstract) {
                access |= ACC_ABSTRACT;
            }
            if (mNative) {
                access |= ACC_NATIVE;

            }
            return access;
        }

        @Override
        public Method setFinal() {
            mAbstract = false;
            return super.setFinal();
        }

        public Method setAbstract() {
            mAbstract = true;
            mFinal = false;
            mNative = false;
            return this;
        }

        public Method setNative() {
            mNative = true;
            mAbstract = false;
            return this;
        }

        public Method setConstructor() {
            mName = "<init>";
            return this;
        }

        public Method setName(String name) {
            mName = name;
            return this;
        }

        public Method setReturnType(String type) {
            mReturnType = Signature.getType(type);
            return this;
        }

        public Method setParameterTypes(String...types) {
            if (types != null) {
                int len = types.length;
                for (int i = 0; i < len; ++i) {
                    types[i] = Signature.getType(types[i]);
                }
                mParameterTypes = types;
            }
            return this;
        }

        public Method setExceptionTypes(String...types) {
            if (types != null) {
                int len = types.length;
                for (int i = 0; i < len; ++i) {
                    types[i] = Signature.getType(types[i]);
                }
                mExceptionTypes = types;
            }
            return this;
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static class Class extends Access<Class> {
        public enum TypeType {
            CLASS, INTERFACE
        }

        boolean mAbstract;
        TypeType mTypeType;
        String mName;
        String mSuper;
        String[] mInterfaces = new String[0];
        private final ArrayList<Class> mInnerClasses = new ArrayList<>();
        private final ArrayList<Field> mFields = new ArrayList<>();
        private final ArrayList<Method> mMethods = new ArrayList<>();

        public List<Class> getInnerClasses() {
            return mInnerClasses;
        }

        public List<Field> getFields() {
            return mFields;
        }

        public List<Method> getMethods() {
            return mMethods;
        }

        @Override
        int getAccess() {
            int access = super.getAccess();
            if (mAbstract) {
                access |= ACC_ABSTRACT;
            }
            if (INTERFACE.equals(mTypeType)) {
                access |= ACC_INTERFACE | ACC_ABSTRACT;
                access &= ~(ACC_FINAL | ACC_SUPER);
            }
            return access;
        }

        @Override
        public Class setFinal() {
            mAbstract = false;
            return super.setFinal();
        }

        public Class setAbstract() {
            mAbstract = true;
            mFinal = false;
            return this;
        }

        public Class setTypeType(TypeType typeType) {
            mTypeType = typeType;
            return this;
        }

        public Class setName(String name) {
            mName = name.replace('.', '/');
            return this;
        }

        public Class setSuper(String name) {
            mSuper = (name == null || name.trim().isEmpty()) ? "java/lang/Object" : name.replace('.', '/');
            return this;
        }

        public Class setInterfaces(String...names) {
            if (names != null) {
                int len = names.length;
                for (int i = 0; i < len; ++i) {
                    names[i] = names[i].replace('.', '/');
                }
                mInterfaces = names;
            }
            return this;
        }
    }

    // simple test: MyGuard.txt
    /*
     * #com.test.MyGuard::
     * com.test.MyGuard:java.lang.Thread:org.xml.Dumper,com.Resources
     * -getName()java.lang.String
     * -setName(java.lang.String)void
     * +setClassLoader(java.lang.ClassLoader,com.abi.Gas,boolean)int
     */
    public static void main(String[] args) {
        String path = args[0];
        String content = "";
        try (InputStream in = new BufferedInputStream(new FileInputStream(new File(path)));
             ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            content = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (!content.trim().isEmpty()) {
            Pattern classPattern = Pattern.compile("([a-zA-Z_0-9.]+):([a-zA-Z_0-9.]*):([a-zA-Z_0-9.,]*)");
            Pattern methodPattern = Pattern.compile("([-+])(\\w+)\\(([a-zA-Z_0-9.,]*)\\)([a-zA-Z_0-9.]+)");
            String[] lines = content.split("\n");
            if (lines.length > 0) {
                NoDepsCompiler compiler = new NoDepsCompiler(8, false);
                boolean begin = false;
                Matcher matcher;
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    matcher = classPattern.matcher(line);
                    if (matcher.matches()) {
                        Class klass = new Class().setTypeType(Class.TypeType.CLASS).setPublic()
                                                 .setName(matcher.group(1)).setSuper(matcher.group(2));
                        String interfaceNames = matcher.group(3);
                        if (interfaceNames != null && !interfaceNames.trim().isEmpty()) {
                            klass.setInterfaces(interfaceNames.split(","));
                        }
                        compiler.beginClass(klass);
                        begin = true;
                    } else {
                        if (!begin) {
                            System.err.println("NO CLASS DEFINE!");
                            return;
                        }
                        matcher = methodPattern.matcher(line);
                        if (matcher.matches()) {
                            Method method =  new Method()
                                    .setPublic().setName(matcher.group(2)).setReturnType(matcher.group(4));
                            boolean isStatic = "+".equals(matcher.group(1));
                            if (isStatic) {
                                method.setStatic();
                            }
                            String methodParams = matcher.group(3);
                            if (methodParams != null && !methodParams.trim().isEmpty()) {
                                method.setParameterTypes(methodParams.split(","));
                            }
                            compiler.addMethodWithThrow(method);
                        } else {
                            System.err.println("NOT MATCH ANY CASE! -> " + line);
                        }
                    }
                }
                compiler.endToFile(path.substring(0, path.lastIndexOf('.')) + ".class");
            }
        }
    }
}
