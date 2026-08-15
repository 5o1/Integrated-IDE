package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;

/** The narrow Logic Programmer API used while materializing one compiled plan step. */
interface LogicProgrammerPlanSink {
    void selectOperator(String operatorId);

    void selectValueType(String valueTypeId);

    void configureLiteral(ExpressionCompiler.CardStep step);
}
