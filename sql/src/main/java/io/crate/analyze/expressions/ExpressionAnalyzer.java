/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.analyze.expressions;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import io.crate.action.sql.Option;
import io.crate.action.sql.SessionContext;
import io.crate.analyze.DataTypeAnalyzer;
import io.crate.analyze.NegativeLiteralVisitor;
import io.crate.analyze.SubscriptContext;
import io.crate.analyze.SubscriptValidator;
import io.crate.analyze.relations.FieldProvider;
import io.crate.analyze.relations.QueriedRelation;
import io.crate.analyze.symbol.Field;
import io.crate.analyze.symbol.Function;
import io.crate.analyze.symbol.Literal;
import io.crate.analyze.symbol.SelectSymbol;
import io.crate.analyze.symbol.Symbol;
import io.crate.analyze.symbol.SymbolType;
import io.crate.analyze.symbol.SymbolVisitors;
import io.crate.analyze.symbol.Symbols;
import io.crate.analyze.symbol.format.SymbolFormatter;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.exceptions.ConversionException;
import io.crate.exceptions.UnsupportedFeatureException;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.FunctionInfo;
import io.crate.metadata.Functions;
import io.crate.metadata.Reference;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.table.Operation;
import io.crate.operation.aggregation.impl.CollectSetAggregation;
import io.crate.operation.operator.AndOperator;
import io.crate.operation.operator.EqOperator;
import io.crate.operation.operator.LikeOperator;
import io.crate.operation.operator.Operator;
import io.crate.operation.operator.OrOperator;
import io.crate.operation.operator.RegexpMatchCaseInsensitiveOperator;
import io.crate.operation.operator.RegexpMatchOperator;
import io.crate.operation.operator.any.AnyLikeOperator;
import io.crate.operation.operator.any.AnyNotLikeOperator;
import io.crate.operation.operator.any.AnyOperator;
import io.crate.operation.predicate.NotPredicate;
import io.crate.operation.scalar.ExtractFunctions;
import io.crate.operation.scalar.SubscriptFunction;
import io.crate.operation.scalar.SubscriptObjectFunction;
import io.crate.operation.scalar.arithmetic.ArrayFunction;
import io.crate.operation.scalar.arithmetic.MapFunction;
import io.crate.operation.scalar.cast.CastFunctionResolver;
import io.crate.operation.scalar.conditional.IfFunction;
import io.crate.operation.scalar.timestamp.CurrentTimestampFunction;
import io.crate.planner.node.dql.Collect;
import io.crate.sql.ExpressionFormatter;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.ArithmeticExpression;
import io.crate.sql.tree.ArrayComparison;
import io.crate.sql.tree.ArrayComparisonExpression;
import io.crate.sql.tree.ArrayLikePredicate;
import io.crate.sql.tree.ArrayLiteral;
import io.crate.sql.tree.AstVisitor;
import io.crate.sql.tree.BetweenPredicate;
import io.crate.sql.tree.BooleanLiteral;
import io.crate.sql.tree.Cast;
import io.crate.sql.tree.ComparisonExpression;
import io.crate.sql.tree.CurrentTime;
import io.crate.sql.tree.DoubleLiteral;
import io.crate.sql.tree.Expression;
import io.crate.sql.tree.Extract;
import io.crate.sql.tree.FunctionCall;
import io.crate.sql.tree.IfExpression;
import io.crate.sql.tree.InListExpression;
import io.crate.sql.tree.InPredicate;
import io.crate.sql.tree.IsNotNullPredicate;
import io.crate.sql.tree.IsNullPredicate;
import io.crate.sql.tree.LikePredicate;
import io.crate.sql.tree.LogicalBinaryExpression;
import io.crate.sql.tree.LongLiteral;
import io.crate.sql.tree.MatchPredicate;
import io.crate.sql.tree.MatchPredicateColumnIdent;
import io.crate.sql.tree.NegativeExpression;
import io.crate.sql.tree.Node;
import io.crate.sql.tree.NotExpression;
import io.crate.sql.tree.NullLiteral;
import io.crate.sql.tree.ObjectLiteral;
import io.crate.sql.tree.ParameterExpression;
import io.crate.sql.tree.QualifiedNameReference;
import io.crate.sql.tree.SearchedCaseExpression;
import io.crate.sql.tree.SimpleCaseExpression;
import io.crate.sql.tree.StringLiteral;
import io.crate.sql.tree.SubqueryExpression;
import io.crate.sql.tree.SubscriptExpression;
import io.crate.sql.tree.TryCast;
import io.crate.sql.tree.WhenClause;
import io.crate.types.ArrayType;
import io.crate.types.BooleanType;
import io.crate.types.CollectionType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.DoubleType;
import io.crate.types.GeoPointType;
import io.crate.types.GeoShapeType;
import io.crate.types.SetType;
import io.crate.types.SingleColumnTableType;
import io.crate.types.StringType;
import io.crate.types.UndefinedType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * <p>This Analyzer can be used to convert Expression from the SQL AST into symbols.</p>
 * <p>
 * <p>
 * In order to resolve QualifiedName or SubscriptExpressions it will use the fieldResolver given in the constructor and
 * generate a relationOutput for the matching Relation.
 * </p>
 */
public class ExpressionAnalyzer {

    private static final Map<ComparisonExpression.Type, ComparisonExpression.Type> SWAP_OPERATOR_TABLE =
        ImmutableMap.<ComparisonExpression.Type, ComparisonExpression.Type>builder()
            .put(ComparisonExpression.Type.GREATER_THAN, ComparisonExpression.Type.LESS_THAN)
            .put(ComparisonExpression.Type.LESS_THAN, ComparisonExpression.Type.GREATER_THAN)
            .put(ComparisonExpression.Type.GREATER_THAN_OR_EQUAL, ComparisonExpression.Type.LESS_THAN_OR_EQUAL)
            .put(ComparisonExpression.Type.LESS_THAN_OR_EQUAL, ComparisonExpression.Type.GREATER_THAN_OR_EQUAL)
            .build();

    private static final NegativeLiteralVisitor NEGATIVE_LITERAL_VISITOR = new NegativeLiteralVisitor();
    private final TransactionContext transactionContext;
    private final java.util.function.Function<ParameterExpression, Symbol> convertParamFunction;
    private final FieldProvider<?> fieldProvider;

    @Nullable
    private final SubqueryAnalyzer subQueryAnalyzer;
    private final Functions functions;
    private final InnerExpressionAnalyzer innerAnalyzer;
    private Operation operation = Operation.READ;

    private static final Pattern SUBSCRIPT_SPLIT_PATTERN = Pattern.compile("^([^\\.\\[]+)(\\.*)([^\\[]*)(\\['.*'\\])");

    public ExpressionAnalyzer(Functions functions,
                              TransactionContext transactionContext,
                              java.util.function.Function<ParameterExpression, Symbol> convertParamFunction,
                              FieldProvider fieldProvider,
                              @Nullable SubqueryAnalyzer subQueryAnalyzer) {
        this.functions = functions;
        this.transactionContext = transactionContext;
        this.convertParamFunction = convertParamFunction;
        this.fieldProvider = fieldProvider;
        this.subQueryAnalyzer = subQueryAnalyzer;
        this.innerAnalyzer = new InnerExpressionAnalyzer();
    }

    /**
     * Converts an expression into a symbol.
     *
     * Expressions like QualifiedName that reference a column are resolved using the fieldResolver that were passed
     * to the constructor.
     *
     * Some information (like resolved function symbols) are written onto the given expressionAnalysisContext.
     *
     * Functions with constants will be normalized.
     */
    public Symbol convert(Expression expression, ExpressionAnalysisContext expressionAnalysisContext) {
        return innerAnalyzer.process(expression, expressionAnalysisContext);
    }

    public Symbol generateQuerySymbol(Optional<Expression> whereExpression, ExpressionAnalysisContext context) {
        if (whereExpression.isPresent()) {
            return convert(whereExpression.get(), context);
        } else {
            return Literal.BOOLEAN_TRUE;
        }
    }

    private FunctionInfo getBuiltinFunctionInfo(String name, List<DataType> argumentTypes) {
        FunctionImplementation impl = functions.getBuiltin(name, argumentTypes);
        if (impl == null) {
            throw Functions.createUnknownFunctionException(name, argumentTypes);
        }
        return impl.info();
    }

    private FunctionInfo getBuiltinOrUdfFunctionInfo(@Nullable String schema, String name, List<DataType> argumentTypes) {
        FunctionImplementation impl;
        if (schema == null) {
            impl = functions.getBuiltin(name, argumentTypes);
            if (impl == null) {
                SessionContext sessionContext = transactionContext.sessionContext();
                impl = functions.getUserDefined(sessionContext.defaultSchema(), name, argumentTypes);
            }
        } else {
            impl = functions.getUserDefined(schema, name, argumentTypes);
        }
        return impl.info();
    }

    protected Symbol convertFunctionCall(FunctionCall node, ExpressionAnalysisContext context) {
        List<Symbol> arguments = new ArrayList<>(node.getArguments().size());
        List<DataType> argumentTypes = new ArrayList<>(node.getArguments().size());
        for (Expression expression : node.getArguments()) {
            Symbol argSymbol = expression.accept(innerAnalyzer, context);


            // TODO mxm apply casting logic here
            argumentTypes.add(argSymbol.valueType());
            arguments.add(argSymbol);
        }

        List<String> parts = node.getName().getParts();
        String schema = null;
        String name;
        if (parts.size() == 1) {
            name = parts.get(0);
        } else {
            schema = parts.get(0);
            name = parts.get(1);
        }

        final FunctionInfo functionInfo;
        if (node.isDistinct()) {
            if (argumentTypes.size() > 1) {
                throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                    "%s(DISTINCT x) does not accept more than one argument", node.getName()));
            }
            // define the inner function. use the arguments/argumentTypes from above
            Symbol innerFunction = allocateFunction(
                getBuiltinOrUdfFunctionInfo(schema, CollectSetAggregation.NAME, argumentTypes),
                arguments,
                context
            );

            // define the outer function which contains the inner function as argument.
            String nodeName = "collection_" + name;
            List<Symbol> outerArguments = ImmutableList.of(innerFunction);
            List<DataType> outerArgumentTypes = ImmutableList.of(new SetType(argumentTypes.get(0))); // can be immutable
            try {
                functionInfo = getBuiltinFunctionInfo(nodeName, outerArgumentTypes);
            } catch (UnsupportedOperationException ex) {
                throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                    "unknown function %s(DISTINCT %s)", name, argumentTypes.get(0)), ex);
            }
            arguments = outerArguments;
        } else {
            functionInfo = getBuiltinOrUdfFunctionInfo(schema, name, argumentTypes);
        }
        return allocateFunction(functionInfo, arguments, context);
    }

    public void setResolveFieldsOperation(Operation operation) {
        this.operation = operation;
    }

    /**
     * Casts if possible, otherwise returns the input Symbol.
     * This may be used to attempt a first cast without failing.
     *
     * The pattern for two Symbol symbol1 and symbol2 would be:
     *
     *      symbol1 = castIfNeeded(symbol1, symbol2.valueType())
     *      symbol2 = castIfNeededOrFail(symbol2, symbol1.valueType())
     *
     * @param symbolToCast Symbol to be casted if possible.
     * @param targetType Target type for the symbol to be casted.
     * @return The casted symbol or the original symbol if casting was not possible
     */
    private static Symbol castIfNeeded(Symbol symbolToCast, DataType targetType) {
        return castIfNeeded(symbolToCast, targetType, false);
    }

    /**
     * Casts a symbol to a target DataType if the symbol can be upcasted and is convertible.
     * Fails otherwise.
     * @param symbolToCast Symbol to be casted.
     * @param targetType Target type for the symbol to be casted.
     * @return The casted symbol.
     * @throws ConversionException If the type doesn't match and upcasting is not possible.
     */
    public static Symbol castIfNeededOrFail(Symbol symbolToCast, DataType targetType) {
        return castIfNeeded(symbolToCast, targetType, true);
    }

    /**
     * Casts a symbol to a target DataType if the symbol can be upcasted and is convertible.
     * @param symbolToCast Symbol to be casted.
     * @param targetType Target type for the symbol to be casted.
     * @param castIsRequired True if the cast is required and we should fail upon failed conversion.
     * @return The casted symbol or the original symbol if casting was not possible and castIsRequired is false.
     * @throws ConversionException If castsIsRequired is true and the type doesn't match and upcasting is not possible.
     */
    public static Symbol castIfNeeded(Symbol symbolToCast, DataType targetType, boolean castIsRequired) {
        DataType sourceType = symbolToCast.valueType();
        if (!symbolToCast.valueType().equals(targetType)) {
            boolean containsField = SymbolVisitors.any(symbol -> symbol instanceof Field, symbolToCast);
            if ((symbolToCast.valueType().id() == UndefinedType.ID || !containsField) &&
                    (sourceType.isConvertableTo(targetType))) {
//                    (sourceType.isConvertableTo(targetType) && targetType.precedes(sourceType))) {
                return cast(symbolToCast, targetType, false);
            }
            if (castIsRequired) {
                throw new ConversionException(symbolToCast, targetType);
            }
        }
        return symbolToCast;
    }

    /**
     * Check if a Symbol can be casted without losing entropy.
     * @param sourceType The symbol type to cast.
     * @param targetType The target type to cast to.
     * @param symbolToCast The symbol to cast. If provided enables to check for Literals
     *                     which may be downcasted.
     * @return True if the cast can be performed, false otherwise.
     */
    private static boolean canBeCasted(DataType sourceType, DataType targetType, @Nullable Symbol symbolToCast) {
        if (targetType.id() == UndefinedType.ID) {
            return false;
        }
        if (DataTypes.isCollectionType(sourceType) && DataTypes.isCollectionType(targetType)) {
            DataType<?> sourceInnerType = ((CollectionType) sourceType).innerType();
            DataType<?> targetInnerType = ((CollectionType) targetType).innerType();
            return canBeCasted(sourceInnerType, targetInnerType, symbolToCast);
        }
        return targetType.precedes(sourceType)                       ||
               checkForSpecialTypeHandling(symbolToCast, targetType) ||
               sourceType.id() == UndefinedType.ID;
    }

    /**
     * Indicates whether it is safe to cast to a type which would requires upcasting.
     * @param sourceSymbol The Symbol to cast to the target type.
     * @param targetType The target type to cast to.
     * @return True if it is safe to cast the symbol to the target type.
     */
    private static boolean checkForSpecialTypeHandling(@Nullable Symbol sourceSymbol, DataType targetType) {
        if (sourceSymbol == null || sourceSymbol.symbolType() != SymbolType.LITERAL) {
            return false;
        }
        DataType sourceType = sourceSymbol.valueType();
        // handles cases of higher to lower type precedence with potential loss of entropy
        if (sourceType.precedes(targetType)) {
            if (sourceType.isNumeric()) {
                Object value = ((Literal)sourceSymbol).value();
                if (DataTypes.isCollectionType(sourceType)) {
                    boolean safe = false;
                    for (Object v : (Object[]) value) {
                        safe |= checkIfValueCanBeUpcasted(v, targetType);
                    }
                    return safe;
                } else {
                    return checkIfValueCanBeUpcasted(value, targetType);
                }
            } else if (DataTypes.isCollectionType(sourceType) && targetType.id() == GeoPointType.ID) {
                return true;
            } else if (sourceType.id() == StringType.ID && targetType.id() == GeoShapeType.ID) {
                return true;
            } else if (sourceType.id() == StringType.ID && targetType.id() == BooleanType.ID) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkIfValueCanBeUpcasted(Object value, DataType dataType) {
        if (DataTypes.isCollectionType(dataType)) {
            return checkIfValueCanBeUpcasted(value, ((CollectionType) dataType).innerType());
        }
        if (dataType.isDecimal()) {
            Number d = (Number) value;
//            return d.doubleValue() == d.floatValue();
            // TODO mxm this seems expensive
            return String.valueOf(d.doubleValue()).equals(String.valueOf(d.floatValue()));
        } else {
            long minValue = Long.MIN_VALUE;
            long maxValue = Long.MAX_VALUE;
            if (value instanceof Short) {
                minValue = Short.MIN_VALUE;
                maxValue = Short.MAX_VALUE;
            } else if (value instanceof Integer) {
                minValue = Integer.MIN_VALUE;
                maxValue = Integer.MAX_VALUE;
            }
            long longValue = ((Number) value).longValue();
            return longValue >= minValue && longValue <= maxValue;
        }
    }


    /**
     * Explicitly cast a Symbol to a type (possible with loss of entropy).
     * @param sourceSymbol The Symbol to cast.
     * @param targetType The type to cast to.
     * @param tryCast True if a try-cast should be attempted. Try casts return null if the cast fails.
     * @return The Symbol wrapped into a cast function for the given {@code targetType}.
     */
    private static Symbol cast(Symbol sourceSymbol, DataType targetType, boolean tryCast) {
        if (sourceSymbol.valueType().equals(targetType)) {
            return sourceSymbol;
        } else if (sourceSymbol.symbolType().isValueSymbol()) {
            return Literal.convert(sourceSymbol, targetType);
        }
        return CastFunctionResolver.generateCastFunction(sourceSymbol, targetType, tryCast);
    }

    @Nullable
    protected static String getQuotedSubscriptLiteral(String nodeName) {
        Matcher matcher = SUBSCRIPT_SPLIT_PATTERN.matcher(nodeName);
        if (matcher.matches()) {
            StringBuilder quoted = new StringBuilder();
            String group1 = matcher.group(1);
            if (!group1.isEmpty()) {
                quoted.append("\"").append(group1).append("\"");
            } else {
                quoted.append(group1);
            }
            String group2 = matcher.group(2);
            String group3 = matcher.group(3);
            if (!group2.isEmpty() && !group3.isEmpty()) {
                quoted.append(matcher.group(2));
                quoted.append("\"").append(group3).append("\"");
            } else if (!group2.isEmpty() && group3.isEmpty()) {
                return null;
            }
            quoted.append(matcher.group(4));
            return quoted.toString();
        } else {
            return null;
        }
    }

    private class InnerExpressionAnalyzer extends AstVisitor<Symbol, ExpressionAnalysisContext> {

        @Override
        protected Symbol visitNode(Node node, ExpressionAnalysisContext context) {
            throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                "Unsupported node %s", node));
        }

        @Override
        protected Symbol visitExpression(Expression node, ExpressionAnalysisContext context) {
            throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                "Unsupported expression %s", ExpressionFormatter.formatStandaloneExpression(node)));
        }

        @Override
        protected Symbol visitCurrentTime(CurrentTime node, ExpressionAnalysisContext context) {
            if (!node.getType().equals(CurrentTime.Type.TIMESTAMP)) {
                visitExpression(node, context);
            }
            List<Symbol> args = Lists.newArrayList(
                Literal.of(node.getPrecision().orElse(CurrentTimestampFunction.DEFAULT_PRECISION))
            );
            return allocateFunction(CurrentTimestampFunction.INFO, args, context);
        }

        @Override
        protected Symbol visitIfExpression(IfExpression node, ExpressionAnalysisContext context) {
            // check for global operand
            Optional<Expression> defaultExpression = node.getFalseValue();
            List<Symbol> arguments = new ArrayList<>(defaultExpression.isPresent() ? 3 : 2);

            arguments.add(node.getCondition().accept(innerAnalyzer, context));
            arguments.add(node.getTrueValue().accept(innerAnalyzer, context));
            if (defaultExpression.isPresent()) {
                arguments.add(defaultExpression.get().accept(innerAnalyzer, context));
            }
            return IfFunction.createFunction(arguments);
        }

        @Override
        protected Symbol visitFunctionCall(FunctionCall node, ExpressionAnalysisContext context) {
            // If it's subscript function then use the special handling
            // and validation that is used for the subscript operator `[]`
            if (node.getName().toString().equalsIgnoreCase(SubscriptFunction.NAME)) {
                assert node.getArguments().size() == 2 : "Number of arguments for subscript function must be 2";
                return visitSubscriptExpression(
                    new SubscriptExpression(node.getArguments().get(0), node.getArguments().get(1)), context);
            }
            return convertFunctionCall(node, context);
        }

        @Override
        protected Symbol visitSimpleCaseExpression(SimpleCaseExpression node, ExpressionAnalysisContext context) {
            return convertCaseExpressionToIfFunctions(node.getWhenClauses(), node.getOperand(),
                node.getDefaultValue(), context);
        }

        @Override
        protected Symbol visitSearchedCaseExpression(SearchedCaseExpression node, ExpressionAnalysisContext context) {
            return convertCaseExpressionToIfFunctions(node.getWhenClauses(), null, node.getDefaultValue(), context);
        }

        private Symbol convertCaseExpressionToIfFunctions(List<WhenClause> whenClauseExpressions,
                                                          @Nullable Expression operandExpression,
                                                          @Nullable Expression defaultValue,
                                                          ExpressionAnalysisContext context) {
            List<Symbol> operands = new ArrayList<>(whenClauseExpressions.size());
            List<Symbol> results = new ArrayList<>(whenClauseExpressions.size());
            Set<DataType> resultsTypes = new HashSet<>(whenClauseExpressions.size());

            // check for global operand
            Symbol operandLeftSymbol = null;
            if (operandExpression != null) {
                operandLeftSymbol = operandExpression.accept(innerAnalyzer, context);
            }

            for (WhenClause whenClause : whenClauseExpressions) {
                Symbol operandSymbol = whenClause.getOperand().accept(innerAnalyzer, context);

                if (operandLeftSymbol != null) {
                    operandSymbol = EqOperator.createFunction(
                        operandLeftSymbol, castIfNeededOrFail(operandSymbol, operandLeftSymbol.valueType()));
                }

                operands.add(operandSymbol);

                Symbol resultSymbol = whenClause.getResult().accept(innerAnalyzer, context);
                results.add(resultSymbol);
                resultsTypes.add(resultSymbol.valueType());
            }

            if (resultsTypes.size() == 2 && !resultsTypes.contains(DataTypes.UNDEFINED)
                || resultsTypes.size() > 2) {
                throw new UnsupportedOperationException(String.format(Locale.ENGLISH,
                    "Data types of all result expressions of a CASE statement must be equal, found: %s",
                    resultsTypes));
            }

            Symbol defaultValueSymbol = null;
            if (defaultValue != null) {
                defaultValueSymbol = defaultValue.accept(innerAnalyzer, context);
            }

            return IfFunction.createChain(operands, results, defaultValueSymbol);
        }

        @Override
        protected Symbol visitCast(Cast node, ExpressionAnalysisContext context) {
            DataType returnType = DataTypeAnalyzer.convert(node.getType());
            return cast(process(node.getExpression(), context), returnType, false);
        }

        @Override
        protected Symbol visitTryCast(TryCast node, ExpressionAnalysisContext context) {
            DataType returnType = DataTypeAnalyzer.convert(node.getType());

            if (CastFunctionResolver.supportsExplicitConversion(returnType)) {
                try {
                    return cast(process(node.getExpression(), context), returnType, true);
                } catch (ConversionException e) {
                    return Literal.NULL;
                }
            }
            throw new IllegalArgumentException(
                String.format(Locale.ENGLISH, "No cast function found for return type %s", returnType.getName()));
        }

        @Override
        protected Symbol visitExtract(Extract node, ExpressionAnalysisContext context) {
            Symbol expression = castIfNeededOrFail(process(node.getExpression(), context), DataTypes.TIMESTAMP);
            Symbol field = castIfNeededOrFail(process(node.getField(), context), DataTypes.STRING);
            return allocateFunction(
                ExtractFunctions.GENERIC_INFO,
                ImmutableList.of(field, expression),
                context);
        }

        @Override
        protected Symbol visitInPredicate(InPredicate node, ExpressionAnalysisContext context) {
            /*
             * convert where x IN (values)
             *
             * where values = a list of expressions or a subquery
             *
             * into
             *
             *      x = ANY(array(1, 2, 3, ...))
             * or
             *      x = ANY(select x from t)
             */
            final Expression arrayExpression;
            Expression valueList = node.getValueList();
            if (valueList instanceof InListExpression) {
                List<Expression> expressions = ((InListExpression) valueList).getValues();
                arrayExpression = new ArrayLiteral(expressions);
            } else {
                arrayExpression = node.getValueList();
            }
            ArrayComparisonExpression arrayComparisonExpression =
                new ArrayComparisonExpression(ComparisonExpression.Type.EQUAL,
                    ArrayComparison.Quantifier.ANY,
                    node.getValue(),
                    arrayExpression);
            return process(arrayComparisonExpression, context);
        }

        @Override
        protected Symbol visitIsNotNullPredicate(IsNotNullPredicate node, ExpressionAnalysisContext context) {
            Symbol argument = process(node.getValue(), context);
            FunctionInfo isNullInfo =
                getBuiltinFunctionInfo(io.crate.operation.predicate.IsNullPredicate.NAME, ImmutableList.of(argument.valueType()));
            return allocateFunction(
                NotPredicate.INFO,
                ImmutableList.of(
                    allocateFunction(
                        isNullInfo,
                        ImmutableList.of(argument),
                        context)),
                context);
        }

        @Override
        protected Symbol visitSubscriptExpression(SubscriptExpression node, ExpressionAnalysisContext context) {
            SubscriptContext subscriptContext = new SubscriptContext();
            SubscriptValidator.validate(node, subscriptContext);
            return resolveSubscriptSymbol(subscriptContext, context);
        }

        Symbol resolveSubscriptSymbol(SubscriptContext subscriptContext, ExpressionAnalysisContext context) {
            // TODO: support nested subscripts as soon as DataTypes.OBJECT elements can be typed
            Symbol subscriptSymbol;
            Expression subscriptExpression = subscriptContext.expression();
            if (subscriptContext.qualifiedName() != null && subscriptExpression == null) {
                subscriptSymbol = fieldProvider.resolveField(subscriptContext.qualifiedName(), subscriptContext.parts(), operation);
            } else if (subscriptExpression != null) {
                subscriptSymbol = subscriptExpression.accept(this, context);
            } else {
                throw new UnsupportedOperationException("Only references, function calls or array literals " +
                                                        "are valid subscript symbols");
            }
            assert subscriptSymbol != null : "subscriptSymbol must not be null";
            Expression index = subscriptContext.index();
            List<String> parts = subscriptContext.parts();
            if (index != null) {
                Symbol indexSymbol = index.accept(this, context);
                // rewrite array access to subscript scalar
                return allocateFunction(
                    getBuiltinFunctionInfo(
                        SubscriptFunction.NAME,
                        ImmutableList.of(subscriptSymbol.valueType(), indexSymbol.valueType())),
                    ImmutableList.of(subscriptSymbol, indexSymbol),
                    context
                );
            } else if (parts != null && subscriptExpression != null) {
                FunctionInfo info = getBuiltinFunctionInfo(SubscriptObjectFunction.NAME,
                    ImmutableList.of(subscriptSymbol.valueType(), DataTypes.STRING));

                Symbol function = allocateFunction(info, ImmutableList.of(subscriptSymbol, Literal.of(parts.get(0))), context);
                for (int i = 1; i < parts.size(); i++) {
                    function = allocateFunction(info, ImmutableList.of(function, Literal.of(parts.get(i))), context);
                }
                return function;
            }
            return subscriptSymbol;
        }

        @Override
        protected Symbol visitLogicalBinaryExpression(LogicalBinaryExpression node, ExpressionAnalysisContext context) {
            final FunctionInfo functionInfo;
            switch (node.getType()) {
                case AND:
                    functionInfo = AndOperator.INFO;
                    break;
                case OR:
                    functionInfo = OrOperator.INFO;
                    break;
                default:
                    throw new UnsupportedOperationException(
                        "Unsupported logical binary expression " + node.getType().name());
            }
            List<Symbol> arguments = ImmutableList.of(
                process(node.getLeft(), context),
                process(node.getRight(), context)
            );
            return allocateFunction(functionInfo, arguments, context);
        }

        @Override
        protected Symbol visitNotExpression(NotExpression node, ExpressionAnalysisContext context) {
            Symbol argument = process(node.getValue(), context);
            return allocateFunction(
                getBuiltinFunctionInfo(NotPredicate.NAME, ImmutableList.of(argument.valueType())),
                ImmutableList.of(argument),
                context);
        }

        @Override
        protected Symbol visitComparisonExpression(ComparisonExpression node, ExpressionAnalysisContext context) {
            Symbol left = process(node.getLeft(), context);
            Symbol right = process(node.getRight(), context);

            Comparison comparison = new Comparison(functions, transactionContext, node.getType(), left, right);
            comparison.normalize(context);
            FunctionIdent ident = comparison.toFunctionIdent();
            return allocateFunction(getBuiltinFunctionInfo(ident.name(), ident.argumentTypes()), comparison.arguments(), context);
        }

        @Override
        public Symbol visitArrayComparisonExpression(ArrayComparisonExpression node, ExpressionAnalysisContext context) {
            if (node.quantifier().equals(ArrayComparisonExpression.Quantifier.ALL)) {
                throw new UnsupportedFeatureException("ALL is not supported");
            }

            context.registerArrayComparisonChild(node.getRight());

            Symbol leftSymbol = process(node.getLeft(), context);
            Symbol arraySymbol = process(node.getRight(), context);

            DataType arraySymbolType = arraySymbol.valueType();

            if (!DataTypes.isCollectionType(arraySymbolType)) {
                throw new IllegalArgumentException(
                    SymbolFormatter.format("invalid array expression: '%s'", arraySymbol));
            }

            DataType rightInnerType = ((CollectionType) arraySymbolType).innerType();
            if (rightInnerType.equals(DataTypes.OBJECT)) {
                throw new IllegalArgumentException("ANY on object arrays is not supported");
            }

            leftSymbol = castIfNeeded(leftSymbol, rightInnerType);
            DataType newCollectionType = ((CollectionType) arraySymbolType).newInstance(leftSymbol.valueType());
            arraySymbol = castIfNeededOrFail(arraySymbol, newCollectionType);

            ComparisonExpression.Type operationType = node.getType();
            String operatorName;
            operatorName = AnyOperator.OPERATOR_PREFIX + operationType.getValue();
            return allocateFunction(
                getBuiltinFunctionInfo(operatorName, ImmutableList.of(leftSymbol.valueType(), arraySymbol.valueType())),
                ImmutableList.of(leftSymbol, arraySymbol),
                context);
        }

        @Override
        public Symbol visitArrayLikePredicate(ArrayLikePredicate node, ExpressionAnalysisContext context) {
            if (node.getEscape() != null) {
                throw new UnsupportedOperationException("ESCAPE is not supported.");
            }
            Symbol rightSymbol = process(node.getValue(), context);
            Symbol leftSymbol = process(node.getPattern(), context);
            DataType rightType = rightSymbol.valueType();

            if (!DataTypes.isCollectionType(rightType)) {
                throw new IllegalArgumentException(
                    SymbolFormatter.format("invalid array expression: '%s'", rightSymbol));
            }

            rightSymbol = castIfNeededOrFail(rightSymbol, new ArrayType(DataTypes.STRING));

            String operatorName = node.inverse() ? AnyNotLikeOperator.NAME : AnyLikeOperator.NAME;

            return allocateFunction(
                getBuiltinFunctionInfo(operatorName, ImmutableList.of(leftSymbol.valueType(), rightSymbol.valueType())),
                ImmutableList.of(leftSymbol, rightSymbol),
                context);
        }

        @Override
        protected Symbol visitLikePredicate(LikePredicate node, ExpressionAnalysisContext context) {
            if (node.getEscape() != null) {
                throw new UnsupportedOperationException("ESCAPE is not supported.");
            }
            Symbol expression = process(node.getValue(), context);
            expression = cast(expression, DataTypes.STRING, false);
            Symbol pattern = castIfNeededOrFail(process(node.getPattern(), context), DataTypes.STRING);
            return allocateFunction(
                getBuiltinFunctionInfo(LikeOperator.NAME, Arrays.asList(expression.valueType(), pattern.valueType())),
                ImmutableList.of(expression, pattern),
                context);
        }

        @Override
        protected Symbol visitIsNullPredicate(IsNullPredicate node, ExpressionAnalysisContext context) {
            Symbol value = process(node.getValue(), context);

            return allocateFunction(
                getBuiltinFunctionInfo(
                    io.crate.operation.predicate.IsNullPredicate.NAME,
                    ImmutableList.of(value.valueType())),
                ImmutableList.of(value),
                context);
        }

        @Override
        protected Symbol visitNegativeExpression(NegativeExpression node, ExpressionAnalysisContext context) {
            // in statements like "where x = -1" the  positive (expression)IntegerLiteral (1)
            // is just wrapped inside a negativeExpression
            // the visitor here swaps it to getBuiltin -1 in a (symbol)LiteralInteger
            return NEGATIVE_LITERAL_VISITOR.process(process(node.getValue(), context), null);
        }

        @Override
        protected Symbol visitArithmeticExpression(ArithmeticExpression node, ExpressionAnalysisContext context) {
            Symbol left = process(node.getLeft(), context);
            Symbol right = process(node.getRight(), context);

            left = castIfNeeded(left, right.valueType());
            right = castIfNeededOrFail(right, left.valueType());

            return allocateFunction(
                getBuiltinFunctionInfo(
                    node.getType().name().toLowerCase(Locale.ENGLISH),
                    Arrays.asList(left.valueType(), right.valueType())),
                ImmutableList.of(left, right),
                context);
        }

        @Override
        protected Symbol visitQualifiedNameReference(QualifiedNameReference node, ExpressionAnalysisContext context) {
            try {
                return fieldProvider.resolveField(node.getName(), null, operation);
            } catch (ColumnUnknownException exception) {
                if (transactionContext.sessionContext().options().contains(Option.ALLOW_QUOTED_SUBSCRIPT)) {
                    String quotedSubscriptLiteral = getQuotedSubscriptLiteral(node.getName().toString());
                    if (quotedSubscriptLiteral != null) {
                        return process(SqlParser.createExpression(quotedSubscriptLiteral), context);
                    } else {
                        throw exception;
                    }
                } else {
                    throw exception;
                }
            }
        }

        @Override
        protected Symbol visitBooleanLiteral(BooleanLiteral node, ExpressionAnalysisContext context) {
            return Literal.of(node.getValue());
        }

        @Override
        protected Symbol visitStringLiteral(StringLiteral node, ExpressionAnalysisContext context) {
            return Literal.of(node.getValue());
        }

        @Override
        protected Symbol visitDoubleLiteral(DoubleLiteral node, ExpressionAnalysisContext context) {
            return Literal.of(node.getValue());
        }

        @Override
        protected Symbol visitLongLiteral(LongLiteral node, ExpressionAnalysisContext context) {
            return Literal.of(node.getValue());
        }

        @Override
        protected Symbol visitNullLiteral(NullLiteral node, ExpressionAnalysisContext context) {
            return Literal.of(UndefinedType.INSTANCE, null);
        }

        @Override
        public Symbol visitArrayLiteral(ArrayLiteral node, ExpressionAnalysisContext context) {
            List<Expression> values = node.values();
            if (values.isEmpty()) {
                return Literal.of(new ArrayType(UndefinedType.INSTANCE), new Object[0]);
            } else {
                List<Symbol> arguments = new ArrayList<>(values.size());
                for (Expression value : values) {
                    arguments.add(process(value, context));
                }
                return allocateFunction(
                    getBuiltinFunctionInfo(ArrayFunction.NAME, Symbols.typeView(arguments)),
                    arguments,
                    context);
            }
        }

        @Override
        public Symbol visitObjectLiteral(ObjectLiteral node, ExpressionAnalysisContext context) {
            Multimap<String, Expression> values = node.values();
            if (values.isEmpty()) {
                return Literal.EMPTY_OBJECT;
            }
            List<Symbol> arguments = new ArrayList<>(values.size() * 2);
            for (Map.Entry<String, Expression> entry : values.entries()) {
                arguments.add(Literal.of(entry.getKey()));
                arguments.add(process(entry.getValue(), context));
            }
            return allocateFunction(
                getBuiltinFunctionInfo(MapFunction.NAME, Symbols.typeView(arguments)),
                arguments,
                context);
        }

        @Override
        public Symbol visitParameterExpression(ParameterExpression node, ExpressionAnalysisContext context) {
            return convertParamFunction.apply(node);
        }

        @Override
        protected Symbol visitBetweenPredicate(BetweenPredicate node, ExpressionAnalysisContext context) {
            // <value> between <min> and <max>
            // -> <value> >= <min> and <value> <= max
            Symbol value = process(node.getValue(), context);
            Symbol min = process(node.getMin(), context);
            Symbol max = process(node.getMax(), context);

            Comparison gte = new Comparison(functions, transactionContext, ComparisonExpression.Type.GREATER_THAN_OR_EQUAL, value, min);
            FunctionIdent gteIdent = gte.normalize(context).toFunctionIdent();
            Symbol gteFunc = allocateFunction(
                getBuiltinFunctionInfo(gteIdent.name(), gteIdent.argumentTypes()),
                gte.arguments(),
                context);

            Comparison lte = new Comparison(functions, transactionContext, ComparisonExpression.Type.LESS_THAN_OR_EQUAL, value, max);
            FunctionIdent lteIdent = lte.normalize(context).toFunctionIdent();
            Symbol lteFunc = allocateFunction(
                getBuiltinFunctionInfo(lteIdent.name(), lteIdent.argumentTypes()),
                lte.arguments(),
                context);

            return AndOperator.of(gteFunc, lteFunc);
        }

        @Override
        public Symbol visitMatchPredicate(MatchPredicate node, ExpressionAnalysisContext context) {
            Map<Field, Symbol> identBoostMap = new HashMap<>(node.idents().size());
            DataType columnType = null;
            for (MatchPredicateColumnIdent ident : node.idents()) {
                Symbol column = process(ident.columnIdent(), context);
                if (columnType == null) {
                    columnType = column.valueType();
                }
                Preconditions.checkArgument(
                    column instanceof Field,
                    SymbolFormatter.format("can only MATCH on columns, not on %s", column));
                Symbol boost = process(ident.boost(), context);
                identBoostMap.put(((Field) column), boost);
            }
            assert columnType != null : "columnType must not be null";
            verifyTypesForMatch(identBoostMap.keySet(), columnType);

            Symbol queryTerm = castIfNeededOrFail(process(node.value(), context), columnType);
            String matchType = io.crate.operation.predicate.MatchPredicate.getMatchType(node.matchType(), columnType);

            List<Symbol> mapArgs = new ArrayList<>(node.properties().size() * 2);
            for (Map.Entry<String, Expression> e : node.properties().properties().entrySet()) {
                mapArgs.add(Literal.of(e.getKey()));
                mapArgs.add(process(e.getValue(), context));
            }
            Symbol options = allocateFunction(MapFunction.createInfo(Symbols.typeView(mapArgs)), mapArgs, context);
            return new io.crate.analyze.symbol.MatchPredicate(identBoostMap, queryTerm, matchType, options);
        }

        @Override
        protected Symbol visitSubqueryExpression(SubqueryExpression node, ExpressionAnalysisContext context) {
            if (subQueryAnalyzer == null) {
                throw new UnsupportedOperationException("Subquery not supported in this statement");
            }
            /* note: This does not support analysis columns in the subquery which belong to the parent relation
             * this would require {@link StatementAnalysisContext#startRelation} to somehow inherit the parent context
             */
            QueriedRelation relation = subQueryAnalyzer.analyze(node.getQuery());
            List<Field> fields = relation.fields();
            if (fields.size() > 1) {
                throw new UnsupportedOperationException("Subqueries with more than 1 column are not supported.");
            }
            /*
             * The SelectSymbol should actually have a RowType as it is a row-expression.
             *
             * But there are no other row-expressions yet. In addition the cast functions and operators don't work with
             * row types (yet).
             *
             * However, we support a single column RowType through the SingleColumnTableType.
             */
            DataType innerType = fields.get(0).valueType();
            SingleColumnTableType dataType = new SingleColumnTableType(innerType);
            final SelectSymbol.ResultType resultType;
            if (context.isArrayComparisonChild(node)) {
                resultType = SelectSymbol.ResultType.SINGLE_COLUMN_MULTIPLE_VALUES;
            } else {
                resultType = SelectSymbol.ResultType.SINGLE_COLUMN_SINGLE_VALUE;
            }
            return new SelectSymbol(relation, dataType, resultType);
        }

    }


    private Symbol allocateFunction(FunctionInfo functionInfo,
                                    List<Symbol> arguments,
                                    ExpressionAnalysisContext context) {
        return allocateFunction(functionInfo, arguments, context, functions, transactionContext);
    }

    /**
     * Creates a function symbol and tries to normalize the new function's
     * {@link FunctionImplementation} iff only Literals are supplied as arguments.
     * This folds any constant expressions like '1 + 1' => '2'.
     * @param functionInfo The meta information about the new function.
     * @param arguments The arguments to provide to the {@link Function}.
     * @param context Context holding the state for the current translation.
     * @param functions The {@link Functions} to normalize constant expressions.
     * @param transactionContext {@link TransactionContext} for this transaction.
     * @return The supplied {@link Function} or a {@link Literal} in case of constant folding.
     */
    static Symbol allocateFunction(FunctionInfo functionInfo,
                                   List<Symbol> arguments,
                                   ExpressionAnalysisContext context,
                                   Functions functions,
                                   TransactionContext transactionContext) {
        if (functionInfo.type() == FunctionInfo.Type.AGGREGATE) {
            context.indicateAggregates();
        }
        Function newFunction = new Function(functionInfo, arguments);
        if (functionInfo.isDeterministic() && Symbols.allLiterals(newFunction)) {
            FunctionImplementation funcImpl = functions.getQualified(newFunction.info().ident());
            return funcImpl.normalizeSymbol(newFunction, transactionContext);
        }
        return newFunction;
    }

    private static void verifyTypesForMatch(Iterable<? extends Symbol> columns, DataType columnType) {
        // TODO mxm do we need this?
        Preconditions.checkArgument(
            io.crate.operation.predicate.MatchPredicate.SUPPORTED_TYPES.contains(columnType),
            String.format(Locale.ENGLISH, "Can only use MATCH on columns of type STRING or GEO_SHAPE, not on '%s'", columnType));
        for (Symbol column : columns) {
            if (!column.valueType().equals(columnType)) {
                throw new IllegalArgumentException(String.format(
                    Locale.ENGLISH,
                    "All columns within a match predicate must be of the same type. Found %s and %s",
                    columnType, column.valueType()));
            }
        }
    }

    private static class Comparison {

        private final Functions functions;
        private final TransactionContext transactionContext;
        private ComparisonExpression.Type comparisonExpressionType;
        private Symbol left;
        private Symbol right;
        private String operatorName;
        private FunctionIdent functionIdent;

        private Comparison(Functions functions,
                           TransactionContext transactionContext,
                           ComparisonExpression.Type comparisonExpressionType,
                           Symbol left,
                           Symbol right) {
            this.functions = functions;
            this.transactionContext = transactionContext;
            this.operatorName = Operator.PREFIX + comparisonExpressionType.getValue();
            this.comparisonExpressionType = comparisonExpressionType;
            this.left = left;
            this.right = right;
        }

        Comparison normalize(ExpressionAnalysisContext context) {
            swapIfNecessary();
            castTypes();
            rewriteNegatingOperators(context);
            return this;
        }

        /**
         * swaps the comparison so that references and fields are on the left side.
         * e.g.:
         * eq(2, name)  becomes  eq(name, 2)
         */
        private void swapIfNecessary() {
            if ((!(right instanceof Reference || right instanceof Field)
                || left instanceof Reference || left instanceof Field)
                && left.valueType().id() != DataTypes.UNDEFINED.id()) {
                return;
            }
            ComparisonExpression.Type type = SWAP_OPERATOR_TABLE.get(comparisonExpressionType);
            if (type != null) {
                comparisonExpressionType = type;
                operatorName = Operator.PREFIX + type.getValue();
            }
            Symbol tmp = left;
            left = right;
            right = tmp;
        }

        private void castTypes() {
            right = castIfNeededOrFail(right, left.valueType());
        }

        /**
         * rewrite   exp1 != exp2  to not(eq(exp1, exp2))
         * and       exp1 !~ exp2  to not(~(exp1, exp2))
         * does nothing if operator != not equals
         */
        private void rewriteNegatingOperators(ExpressionAnalysisContext context) {
            final String opName;
            final DataType opType;
            switch (comparisonExpressionType) {
                case NOT_EQUAL:
                    opName = EqOperator.NAME;
                    opType = EqOperator.RETURN_TYPE;
                    break;
                case REGEX_NO_MATCH:
                    opName = RegexpMatchOperator.NAME;
                    opType = RegexpMatchOperator.RETURN_TYPE;
                    break;
                case REGEX_NO_MATCH_CI:
                    opName = RegexpMatchCaseInsensitiveOperator.NAME;
                    opType = RegexpMatchCaseInsensitiveOperator.RETURN_TYPE;
                    break;

                default:
                    return; // non-negating comparison
            }
            FunctionIdent ident = new FunctionIdent(
                opName,
                ImmutableList.of(left.valueType(), right.valueType()));
            left = allocateFunction(
                new FunctionInfo(ident, opType),
                ImmutableList.of(left, right),
                context,
                functions,
                transactionContext);
            right = null;
            functionIdent = NotPredicate.INFO.ident();
            operatorName = NotPredicate.NAME;
        }

        FunctionIdent toFunctionIdent() {
            if (functionIdent == null) {
                return new FunctionIdent(
                    operatorName,
                    Arrays.asList(left.valueType(), right.valueType()));
            }
            return functionIdent;
        }

        List<Symbol> arguments() {
            if (right == null) {
                // this is the case if the comparison has been rewritten to not(eq(exp1, exp2))
                return ImmutableList.of(left);
            }
            return ImmutableList.of(left, right);
        }
    }
}
