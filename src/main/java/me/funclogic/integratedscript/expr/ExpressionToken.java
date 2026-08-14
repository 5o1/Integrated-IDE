package me.funclogic.integratedscript.expr;

record ExpressionToken(ExpressionToken.Type type, String text, int position) {
    enum Type {
        IDENTIFIER, STRING, INTEGER, DECIMAL, BOOLEAN, ITEM, MOD, TAG,
        LBRACE, RBRACE, DOT, LPAREN, RPAREN, COMMA, EQUALS, NEWLINE, EOF
    }
}
