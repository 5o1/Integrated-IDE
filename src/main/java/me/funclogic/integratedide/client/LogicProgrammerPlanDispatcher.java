package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;

/** Maps a compiler step to the minimal actions accepted by a Logic Programmer. */
final class LogicProgrammerPlanDispatcher {
    private LogicProgrammerPlanDispatcher() {
    }

    static void select(ExpressionCompiler.CardStep step, LogicProgrammerPlanSink sink) {
        if (step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR) {
            sink.selectOperator(step.value());
        } else if (step.kind() != ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
            sink.selectValueType(step.outputTypeId());
        }
    }

    static void configure(ExpressionCompiler.CardStep step, LogicProgrammerPlanSink sink) {
        if (step.kind() != ExpressionCompiler.StepKind.DYNAMIC_OPERATOR
                && step.kind() != ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
            sink.configureLiteral(step);
        }
    }
}
