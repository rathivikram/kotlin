/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license 
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.*
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.ConeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.FirImplicitBuiltinType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.Printer

fun FirElement.render(): String = buildString { this@render.accept(FirRenderer(this)) }

class FirRenderer(builder: StringBuilder) : FirVisitorVoid() {
    private val printer = Printer(builder)

    private var lineBeginning = true

    private fun print(vararg objects: Any) {
        if (lineBeginning) {
            lineBeginning = false
            printer.print(*objects)
        } else {
            printer.printWithNoIndent(*objects)
        }
    }

    private fun println(vararg objects: Any) {
        print(*objects)
        printer.printlnWithNoIndent()
        lineBeginning = true
    }

    private fun pushIndent() {
        printer.pushIndent()
    }

    private fun popIndent() {
        printer.popIndent()
    }

    override fun visitElement(element: FirElement) {
        element.acceptChildren(this)
    }

    override fun visitFile(file: FirFile) {
        println("FILE: ${file.name}")
        pushIndent()
        super.visitFile(file)
        popIndent()
    }

    private fun List<FirElement>.renderSeparated() {
        for ((index, element) in this.withIndex()) {
            if (index > 0) {
                print(", ")
            }
            element.accept(this@FirRenderer)
        }
    }

    private fun List<FirValueParameter>.renderParameters() {
        print("(")
        renderSeparated()
        print(")")
    }

    private fun List<FirAnnotationCall>.renderAnnotations() {
        for (annotation in this) {
            visitAnnotationCall(annotation)
        }
    }

    private fun Variance.renderVariance() {
        label.let {
            print(it)
            if (it.isNotEmpty()) {
                print(" ")
            }
        }
    }

    override fun visitCallableMember(callableMember: FirCallableMember) {
        visitMemberDeclaration(callableMember)
        val receiverType = callableMember.receiverType
        if (receiverType != null) {
            print(" ")
            receiverType.accept(this)
            print(".")
        }
        if (callableMember is FirFunction) {
            callableMember.valueParameters.renderParameters()
        } else if (callableMember is FirProperty) {
            print(if (callableMember.isVar) "(var)" else "(val)")
        }
        print(": ")
        callableMember.returnType.accept(this)
    }

    private fun Visibility.asString() =
        when (this) {
            Visibilities.UNKNOWN -> "public?"
            else -> toString()
        }

    private fun FirMemberDeclaration.modalityAsString(): String {
        return modality?.name?.toLowerCase() ?: run {
            if (this is FirCallableMember && this.isOverride) {
                "open?"
            } else {
                "final?"
            }
        }
    }

    override fun visitMemberDeclaration(memberDeclaration: FirMemberDeclaration) {
        memberDeclaration.annotations.renderAnnotations()
        if (memberDeclaration.typeParameters.isNotEmpty()) {
            print("<")
            memberDeclaration.typeParameters.renderSeparated()
            print("> ")
        }
        print(memberDeclaration.visibility.asString() + " " + memberDeclaration.modalityAsString() + " ")
        if (memberDeclaration.isExpect) {
            print("expect ")
        }
        if (memberDeclaration.isActual) {
            print("actual ")
        }
        if (memberDeclaration is FirCallableMember && memberDeclaration.isOverride) {
            print("override ")
        }
        if (memberDeclaration is FirRegularClass) {
            if (memberDeclaration.isInner) {
                print("inner ")
            }
            if (memberDeclaration.isCompanion) {
                print("companion ")
            }
            if (memberDeclaration.isData) {
                print("data ")
            }
            if (memberDeclaration.isInline) {
                print("inline ")
            }
        } else if (memberDeclaration is FirNamedFunction) {
            if (memberDeclaration.isOperator) {
                print("operator ")
            }
            if (memberDeclaration.isInfix) {
                print("infix ")
            }
            if (memberDeclaration.isInline) {
                print("inline ")
            }
            if (memberDeclaration.isTailRec) {
                print("tailrec ")
            }
            if (memberDeclaration.isExternal) {
                print("external ")
            }
            if (memberDeclaration.isSuspend) {
                print("suspend ")
            }
        } else if (memberDeclaration is FirProperty) {
            if (memberDeclaration.isConst) {
                print("const ")
            }
            if (memberDeclaration.isLateInit) {
                print("lateinit ")
            }
        }

        visitNamedDeclaration(memberDeclaration)
    }

    override fun visitNamedDeclaration(namedDeclaration: FirNamedDeclaration) {
        visitDeclaration(namedDeclaration)
        print(" " + namedDeclaration.name)
    }

    override fun visitDeclaration(declaration: FirDeclaration) {
        print(
            when (declaration) {
                is FirRegularClass -> declaration.classKind.name.toLowerCase().replace("_", " ")
                is FirTypeAlias -> "typealias"
                is FirNamedFunction -> "function"
                is FirProperty -> "property"
                is FirVariable -> if (declaration.isVal) "val" else "var"
                else -> "unknown"
            }
        )
    }

    override fun visitEnumEntry(enumEntry: FirEnumEntry) {
        visitRegularClass(enumEntry)
    }

    private fun FirDeclarationContainer.renderDeclarations() {
        println(" {")
        pushIndent()
        for (declaration in declarations) {
            declaration.accept(this@FirRenderer)
            println()
        }
        popIndent()
        println("}")
    }

    override fun visitRegularClass(regularClass: FirRegularClass) {
        visitMemberDeclaration(regularClass)
        if (regularClass.superTypes.isNotEmpty()) {
            print(" : ")
            regularClass.superTypes.renderSeparated()
        }
        regularClass.renderDeclarations()
    }

    override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) {
        anonymousObject.annotations.renderAnnotations()
        print("object : ")
        anonymousObject.superTypes.renderSeparated()
        anonymousObject.renderDeclarations()
    }

    override fun visitVariable(variable: FirVariable) {
        variable.annotations.renderAnnotations()
        visitNamedDeclaration(variable)
        print(": ")
        variable.returnType.accept(this)
        variable.initializer?.let {
            print(" = ")
            it.accept(this)
        }
    }

    override fun visitProperty(property: FirProperty) {
        visitCallableMember(property)
        property.initializer?.let {
            print(" = ")
            it.accept(this)
        }
        pushIndent()
        property.delegate?.let {
            print("by ")
            it.accept(this)
        }
        println()
        property.getter.accept(this)
        if (property.getter.body == null) {
            println()
        }
        if (property.isVar) {
            property.setter.accept(this)
            if (property.setter.body == null) {
                println()
            }
        }
        popIndent()
    }

    override fun visitNamedFunction(namedFunction: FirNamedFunction) {
        visitCallableMember(namedFunction)
        namedFunction.body?.accept(this)
        if (namedFunction.body == null) {
            println()
        }
    }

    override fun visitConstructor(constructor: FirConstructor) {
        constructor.annotations.renderAnnotations()
        print(constructor.visibility.asString() + " constructor")
        constructor.valueParameters.renderParameters()
        constructor.delegatedConstructor?.accept(this)
        constructor.body?.accept(this)
        if (constructor.body == null) {
            println()
        }
    }

    override fun visitPropertyAccessor(propertyAccessor: FirPropertyAccessor) {
        propertyAccessor.annotations.renderAnnotations()
        print(propertyAccessor.visibility.asString() + " ")
        print(if (propertyAccessor.isGetter) "get" else "set")
        propertyAccessor.valueParameters.renderParameters()
        print(": ")
        propertyAccessor.returnType.accept(this)
        propertyAccessor.body?.accept(this)
    }

    override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
        anonymousFunction.annotations.renderAnnotations()
        val label = anonymousFunction.label
        if (label != null) {
            print("${label.name}@")
        }
        print("function ")
        val receiverType = anonymousFunction.receiverType
        if (receiverType != null) {
            print(" ")
            receiverType.accept(this)
            print(".")
        }
        print("<anonymous>")
        anonymousFunction.valueParameters.renderParameters()
        print(": ")
        anonymousFunction.returnType.accept(this)
        anonymousFunction.body?.accept(this)
    }

    override fun visitFunction(function: FirFunction) {
        function.valueParameters.renderParameters()
        visitDeclarationWithBody(function)
    }

    override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer) {
        print("init")
        anonymousInitializer.body?.accept(this)
    }

    override fun visitDeclarationWithBody(declarationWithBody: FirDeclarationWithBody) {
        visitDeclaration(declarationWithBody)
        declarationWithBody.body?.accept(this)
    }

    override fun visitBlock(block: FirBlock) {
        println(" {")
        pushIndent()
        for (statement in block.statements) {
            statement.accept(this)
            println()
        }
        popIndent()
        println("}")
    }

    override fun visitTypeAlias(typeAlias: FirTypeAlias) {
        typeAlias.annotations.renderAnnotations()
        visitMemberDeclaration(typeAlias)
        print(" = ")
        typeAlias.expandedType.accept(this)
        println()
    }

    override fun visitTypeParameter(typeParameter: FirTypeParameter) {
        typeParameter.annotations.renderAnnotations()
        if (typeParameter.isReified) {
            print("reified ")
        }
        typeParameter.variance.renderVariance()
        print(typeParameter.name)
        if (typeParameter.bounds.isNotEmpty()) {
            print(" : ")
            typeParameter.bounds.renderSeparated()
        }
    }

    override fun visitTypedDeclaration(typedDeclaration: FirTypedDeclaration) {
        visitDeclaration(typedDeclaration)
    }

    override fun visitValueParameter(valueParameter: FirValueParameter) {
        valueParameter.annotations.renderAnnotations()
        if (valueParameter.isCrossinline) {
            print("crossinline ")
        }
        if (valueParameter.isNoinline) {
            print("noinline ")
        }
        if (valueParameter.isVararg) {
            print("vararg ")
        }
        if (valueParameter.name != SpecialNames.NO_NAME_PROVIDED) {
            print(valueParameter.name.toString() + ": ")
        }
        valueParameter.returnType.accept(this)
        valueParameter.defaultValue?.let {
            print(" = ")
            it.accept(this)
        }
    }

    override fun visitImport(import: FirImport) {
        visitElement(import)
    }

    override fun visitStatement(statement: FirStatement) {
        visitElement(statement)
    }

    override fun visitReturnStatement(returnStatement: FirReturnStatement) {
        print("return")
        val target = returnStatement.target
        val labeledElement = target.labeledElement
        if (labeledElement is FirNamedFunction) {
            print("@@@${labeledElement.name}")
        } else {
            val labelName = target.labelName
            if (labelName != null) {
                if (labeledElement is FirAnonymousFunction) {
                    print("@@")
                }
                print("@$labelName")
            }
        }
        print(" ")
        returnStatement.result.accept(this)
    }

    override fun visitWhenBranch(whenBranch: FirWhenBranch) {
        val condition = whenBranch.condition
        if (condition is FirElseIfTrueCondition) {
            print("else")
        } else {
            condition.accept(this)
        }
        print(" -> ")
        whenBranch.result.accept(this)
    }

    override fun visitWhenExpression(whenExpression: FirWhenExpression) {
        print("when (")
        val subjectVariable = whenExpression.subjectVariable
        if (subjectVariable != null) {
            subjectVariable.accept(this)
        } else {
            whenExpression.subject?.accept(this)
        }
        println(") {")
        pushIndent()
        for (branch in whenExpression.branches) {
            branch.accept(this)
        }
        popIndent()
        println("}")
    }

    override fun visitTryExpression(tryExpression: FirTryExpression) {
        print("try")
        tryExpression.tryBlock.accept(this)
        for (catchClause in tryExpression.catches) {
            print("catch (")
            catchClause.parameter.accept(this)
            print(")")
            catchClause.block.accept(this)
        }
        val finallyBlock = tryExpression.finallyBlock ?: return
        print("finally")
        finallyBlock.accept(this)
    }

    override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop) {
        val label = doWhileLoop.label
        if (label != null) {
            print("${label.name}@")
        }
        print("do")
        doWhileLoop.block.accept(this)
        print("while(")
        doWhileLoop.condition.accept(this)
        print(")")
    }

    override fun visitWhileLoop(whileLoop: FirWhileLoop) {
        val label = whileLoop.label
        if (label != null) {
            print("${label.name}@")
        }
        print("while(")
        whileLoop.condition.accept(this)
        print(")")
        whileLoop.block.accept(this)
    }

    private fun visitLoopJump(jump: FirJump<FirLoop>) {
        val target = jump.target
        val labeledElement = target.labeledElement
        print("@@@[")
        labeledElement.condition.accept(this)
        print("] ")
    }

    override fun visitBreakStatement(breakStatement: FirBreakStatement) {
        print("break")
        visitLoopJump(breakStatement)
    }

    override fun visitContinueStatement(continueStatement: FirContinueStatement) {
        print("continue")
        visitLoopJump(continueStatement)
    }

    override fun visitExpression(expression: FirExpression) {
        print(
            when (expression) {
                is FirExpressionStub -> "STUB"
                is FirUnitExpression -> "Unit"
                is FirWhenSubjectExpression -> "\$subj\$"
                is FirElseIfTrueCondition -> "else"
                else -> "??? ${expression.javaClass}"
            }
        )
    }

    override fun <T> visitConstExpression(constExpression: FirConstExpression<T>) {
        print("${constExpression.kind}(${constExpression.value})")
    }

    override fun visitCall(call: FirCall) {
        print("(")
        call.arguments.renderSeparated()
        print(")")
    }

    override fun visitAnnotationCall(annotationCall: FirAnnotationCall) {
        print("@")
        annotationCall.useSiteTarget?.let {
            print(it.name)
            print(":")
        }
        annotationCall.annotationType.accept(this)
        visitCall(annotationCall)
        if (annotationCall.useSiteTarget == AnnotationUseSiteTarget.FILE) {
            println()
        } else {
            print(" ")
        }
    }

    override fun visitDelegatedConstructorCall(delegatedConstructorCall: FirDelegatedConstructorCall) {
        if (delegatedConstructorCall.isSuper) {
            print(": super<")
        } else if (delegatedConstructorCall.isThis) {
            print(": this<")
        }
        delegatedConstructorCall.constructedType.accept(this)
        print(">")
        visitCall(delegatedConstructorCall)
    }

    override fun visitType(type: FirType) {
        type.annotations.renderAnnotations()
        visitElement(type)
    }

    override fun visitDelegatedType(delegatedType: FirDelegatedType) {
        delegatedType.type.accept(this)
        print(" by ")
        delegatedType.delegate?.accept(this)
    }

    override fun visitErrorType(errorType: FirErrorType) {
        visitType(errorType)
        print("<ERROR TYPE: ${errorType.reason}>")
    }

    override fun visitImplicitType(implicitType: FirImplicitType) {
        print(if (implicitType is FirImplicitBuiltinType) "kotlin.${implicitType.name}" else "<implicit>")
    }

    override fun visitTypeWithNullability(typeWithNullability: FirTypeWithNullability) {
        if (typeWithNullability.isNullable) {
            print("?")
        }
    }

    override fun visitDynamicType(dynamicType: FirDynamicType) {
        dynamicType.annotations.renderAnnotations()
        print("<dynamic>")
        visitTypeWithNullability(dynamicType)
    }

    override fun visitFunctionType(functionType: FirFunctionType) {
        print("( ")
        functionType.receiverType?.let {
            it.accept(this)
            print(".")
        }
        functionType.valueParameters.renderParameters()
        print(" -> ")
        functionType.returnType.accept(this)
        print(" )")
        visitTypeWithNullability(functionType)
    }

    private fun ConeSymbol.asString(): String {
        return when (this) {
            is ConeClassLikeSymbol -> classId.asString()
            is FirTypeParameterSymbol -> fir.name.asString()
            else -> "Unsupported: ${this::class}"
        }
    }

    private fun ConeKotlinType.asString(): String {
        return when (this) {
            is ConeKotlinErrorType -> "error: $reason"
            is ConeClassLikeType -> {
                val sb = StringBuilder()
                sb.append(symbol.classId.asString())
                if (typeArguments.isNotEmpty()) {
                    sb.append(typeArguments.joinToString(prefix = "<", postfix = ">") { it ->
                        when (it) {
                            StarProjection -> "*"
                            is ConeKotlinTypeProjectionIn -> "in ${it.type.asString()}"
                            is ConeKotlinTypeProjectionOut -> "out ${it.type.asString()}"
                            is ConeKotlinType -> it.asString()
                        }
                    })
                }
                if (this is ConeAbbreviatedType) {
                    sb.append(" = ${this.directExpansion.asString()}")
                }
                sb.toString()
            }
            is ConeTypeParameterType -> {
                symbol.asString()
            }
            is ConeFunctionType -> {
                buildString {
                    receiverType?.let {
                        append(it.asString())
                        append(".")
                    }
                    append("(")
                    parameterTypes.joinTo(this) { it.asString() }
                    append(") -> ")
                    append(returnType.asString())
                }
            }
        }
    }

    override fun visitResolvedType(resolvedType: FirResolvedType) {
        resolvedType.annotations.renderAnnotations()
        print("R|")
        val coneType = resolvedType.type
        print(coneType.asString())
        print("|")
        visitTypeWithNullability(resolvedType)
    }

    override fun visitUserType(userType: FirUserType) {
        userType.annotations.renderAnnotations()
        for ((index, qualifier) in userType.qualifier.withIndex()) {
            if (index != 0) {
                print(".")
            }
            print(qualifier.name)
            if (qualifier.typeArguments.isNotEmpty()) {
                print("<")
                qualifier.typeArguments.renderSeparated()
                print(">")
            }
        }
        visitTypeWithNullability(userType)
    }

    override fun visitTypeProjection(typeProjection: FirTypeProjection) {
        visitElement(typeProjection)
    }

    override fun visitTypeProjectionWithVariance(typeProjectionWithVariance: FirTypeProjectionWithVariance) {
        typeProjectionWithVariance.variance.renderVariance()
        typeProjectionWithVariance.type.accept(this)
    }

    override fun visitStarProjection(starProjection: FirStarProjection) {
        print("*")
    }

    override fun visitMemberAccess(memberAccess: FirMemberAccess) {
        val explicitReceiver = memberAccess.explicitReceiver
        if (explicitReceiver != null) {
            explicitReceiver.accept(this)
            if (memberAccess.safe) {
                print("?.")
            } else {
                print(".")
            }
        }
    }

    override fun visitPropertyGet(propertyGet: FirPropertyGet) {
        visitMemberAccess(propertyGet)
        print("${propertyGet.calleeReference.name}#")
    }

    override fun visitPropertySet(propertySet: FirPropertySet) {
        visitMemberAccess(propertySet)
        print("${propertySet.calleeReference.name}# ")
        print(propertySet.operation.operator)
        print(" ")
        propertySet.value.accept(this)
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        visitMemberAccess(functionCall)
        print("${functionCall.calleeReference.name}#")
        visitCall(functionCall)
    }

    override fun visitOperatorCall(operatorCall: FirOperatorCall) {
        print(operatorCall.operation.operator)
        if (operatorCall is FirTypeOperatorCall) {
            print("/")
            operatorCall.type.accept(this)
        }
        visitCall(operatorCall)
    }

    override fun visitComponentCall(componentCall: FirComponentCall) {
        print("component${componentCall.componentIndex}")
        visitCall(componentCall)
    }

    override fun visitThrowExpression(throwExpression: FirThrowExpression) {
        print("throw ")
        throwExpression.exception.accept(this)
    }

    override fun visitErrorExpression(errorExpression: FirErrorExpression) {
        print("ERROR_EXPR(${errorExpression.reason})")
    }
}