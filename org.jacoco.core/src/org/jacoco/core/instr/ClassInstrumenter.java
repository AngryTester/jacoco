/*******************************************************************************
 * Copyright (c) 2009, 2010 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *    
 * $Id: $
 *******************************************************************************/
package org.jacoco.core.instr;

import static java.lang.String.format;

import org.jacoco.core.runtime.IExecutionDataAccessorGenerator;
import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adapter that instruments a class for coverage tracing.
 * 
 * @author Marc R. Hoffmann
 * @version $Revision: $
 */
class ClassInstrumenter extends ClassAdapter implements IBlockClassVisitor {

	private static final Object[] STACK_ARRZ = new Object[] { InstrSupport.DATAFIELD_DESC };

	private final long id;

	private final IExecutionDataAccessorGenerator accessorGenerator;

	private IProbeArrayStrategy probeArrayStrategy;

	private String className;

	/**
	 * Emits a instrumented version of this class to the given class visitor.
	 * 
	 * @param id
	 *            unique identifier given to this class
	 * @param accessorGenerator
	 *            this generator will be used for instrumentation
	 * @param cv
	 *            next delegate in the visitor chain will receive the
	 *            instrumented class
	 */
	public ClassInstrumenter(final long id,
			final IExecutionDataAccessorGenerator accessorGenerator,
			final ClassVisitor cv) {
		super(cv);
		this.id = id;
		this.accessorGenerator = accessorGenerator;
	}

	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName,
			final String[] interfaces) {
		this.className = name;
		if ((access & Opcodes.ACC_INTERFACE) == 0) {
			this.probeArrayStrategy = new ClassTypeStrategy();
		} else {
			this.probeArrayStrategy = new InterfaceTypeStrategy();
		}
		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public FieldVisitor visitField(final int access, final String name,
			final String desc, final String signature, final Object value) {
		assertNotInstrumented(name, InstrSupport.DATAFIELD_NAME);
		return super.visitField(access, name, desc, signature, value);
	}

	@Override
	public IBlockMethodVisitor visitMethod(final int access, final String name,
			final String desc, final String signature, final String[] exceptions) {

		assertNotInstrumented(name, InstrSupport.INITMETHOD_NAME);

		final MethodVisitor mv = super.visitMethod(access, name, desc,
				signature, exceptions);

		if (mv == null) {
			return null;
		}
		return new MethodInstrumenter(mv, access, desc, probeArrayStrategy);
	}

	public void visitTotalProbeCount(final int count) {
		probeArrayStrategy.addMembers(cv, count);
	}

	/**
	 * Ensures that the given member does not correspond to a internal member
	 * created by the instrumentation process. This would mean that the class
	 * has been instrumented twice.
	 * 
	 * @param member
	 *            name of the member to check
	 * @param instrMember
	 *            name of a instrumentation member
	 * @throws IllegalStateException
	 *             thrown if the member has the same name than the
	 *             instrumentation member
	 */
	private void assertNotInstrumented(final String member,
			final String instrMember) throws IllegalStateException {
		if (member.equals(instrMember)) {
			throw new IllegalStateException(format(
					"Class %s is already instrumented.", className));
		}
	}

	// === probe array strategies ===

	private class ClassTypeStrategy implements IProbeArrayStrategy {

		public int pushInstance(final MethodVisitor mv) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, className,
					InstrSupport.INITMETHOD_NAME, InstrSupport.INITMETHOD_DESC);
			return 1;
		}

		public void addMembers(final ClassVisitor delegate, final int probeCount) {
			createDataField();
			createInitMethod(probeCount);
		}

		private void createDataField() {
			cv.visitField(InstrSupport.DATAFIELD_ACC,
					InstrSupport.DATAFIELD_NAME, InstrSupport.DATAFIELD_DESC,
					null, null);
		}

		private void createInitMethod(final int probeCount) {
			final MethodVisitor mv = cv.visitMethod(
					InstrSupport.INITMETHOD_ACC, InstrSupport.INITMETHOD_NAME,
					InstrSupport.INITMETHOD_DESC, null, null);

			// Load the value of the static data field:
			mv.visitFieldInsn(Opcodes.GETSTATIC, className,
					InstrSupport.DATAFIELD_NAME, InstrSupport.DATAFIELD_DESC);
			mv.visitInsn(Opcodes.DUP);

			// Stack[1]: [Z
			// Stack[0]: [Z

			// Skip initialization when we already have a data array:
			final Label alreadyInitialized = new Label();
			mv.visitJumpInsn(Opcodes.IFNONNULL, alreadyInitialized);

			// Stack[0]: [Z

			mv.visitInsn(Opcodes.POP);
			final int size = genInitializeDataField(mv, probeCount);

			// Stack[0]: [Z

			// Return the method's block array:
			mv.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, STACK_ARRZ);
			mv.visitLabel(alreadyInitialized);
			mv.visitInsn(Opcodes.ARETURN);

			mv.visitMaxs(Math.max(size, 2), 1); // Maximum local stack size is 2
			mv.visitEnd();
		}

		/**
		 * Generates the byte code to initialize the static coverage data field
		 * within this class.
		 * 
		 * The code will push the [Z data array on the operand stack.
		 * 
		 * @param mv
		 *            generator to emit code to
		 */
		private int genInitializeDataField(final MethodVisitor mv,
				final int probeCount) {
			final int size = accessorGenerator.generateDataAccessor(id,
					className, probeCount, mv);

			// Stack[0]: [Z

			mv.visitInsn(Opcodes.DUP);

			// Stack[1]: [Z
			// Stack[0]: [Z

			mv.visitFieldInsn(Opcodes.PUTSTATIC, className,
					InstrSupport.DATAFIELD_NAME, InstrSupport.DATAFIELD_DESC);

			// Stack[0]: [Z

			return Math.max(size, 2); // Maximum local stack size is 2
		}
	}

	private class InterfaceTypeStrategy implements IProbeArrayStrategy {

		public int pushInstance(final MethodVisitor mv) {
			// TODO As we don't know the actual probe count at this point in
			// time use a hard coded value of 64. This is typically be way too
			// big and will break static initializers in interfaces with more
			// than 64 blocks. So this has to be replaced with the actual probe
			// count.
			final int size = accessorGenerator.generateDataAccessor(id,
					className, 64, mv);
			return size;
		}

		public void addMembers(final ClassVisitor delegate, final int probeCount) {
		}

	}

}
