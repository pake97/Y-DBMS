grammar CausalDSL;

// ─── Program ──────────────────────────────────────────────────────────────────

program : statement* EOF ;

statement
    : defineVariableStmt
    | defineModelStmt
    | causesOfStmt
    | effectsOfStmt
    | ancestorsOfStmt
    | descendantsOfStmt
    | mediatorsBetweenStmt
    | collidersBetweenStmt
    | confoundersOfStmt
    | backdoorPathsStmt
    | identifyStmt
    | estimateStmt
    | interveneStmt
    | counterfactualStmt
    | useModelStmt
    | resetStmt
    | showDagStmt
    | describeVariableStmt
    ;

// ─── Definitions ──────────────────────────────────────────────────────────────

// Standalone:  DEFINE CAUSAL VARIABLE Treatment FOR UNIT User OBSERVED AS "..." TYPE binary ROLE treatment
defineVariableStmt
    : DEFINE CAUSAL VARIABLE name=ID
      (FOR UNIT unit=ID)?
      (OBSERVED AS observedAs=STRING)?
      (TYPE varType=variableType)?
      (ROLE varRole=variableRole)?
    ;

// Block form:  DEFINE MODEL Name { VARIABLE ... DAG { ... } }
// DAG-only form: DEFINE CAUSAL DAG Name { A -> B  B -> C }
defineModelStmt
    : DEFINE MODEL name=ID LBRACE variableDecl* dagBlock RBRACE   # defineModelBlock
    | DEFINE CAUSAL DAG name=ID LBRACE edge+ RBRACE               # defineCausalDagBlock
    ;

variableDecl
    : VARIABLE name=ID
      (FOR UNIT unit=ID)?
      (OBSERVED AS observedAs=STRING)?
      (TYPE varType=variableType)?
      (ROLE varRole=variableRole)?
    ;

dagBlock : DAG LBRACE edge+ RBRACE ;

edge : from=ID ARROW to=ID ;

// ─── Structural queries ───────────────────────────────────────────────────────

causesOfStmt           : CAUSES OF var=ID ;
effectsOfStmt          : EFFECTS OF var=ID ;
ancestorsOfStmt        : ANCESTORS OF var=ID ;
descendantsOfStmt      : DESCENDANTS OF var=ID ;
mediatorsBetweenStmt   : MEDIATORS BETWEEN from=ID AND to=ID ;
collidersBetweenStmt   : COLLIDERS BETWEEN from=ID AND to=ID ;
confoundersOfStmt      : CONFOUNDERS OF from=ID AND to=ID ;
backdoorPathsStmt      : BACKDOOR PATHS BETWEEN from=ID AND to=ID ;

// ─── Identification ───────────────────────────────────────────────────────────

identifyStmt
    : IDENTIFY EFFECT OF treatment=ID ON outcome=ID
      (RETURN ADJUSTMENT SETS)?
      EXPLAIN?
    ;

// ─── Estimation ───────────────────────────────────────────────────────────────

estimateStmt
    : ESTIMATE EFFECT OF treatment=ID ON outcome=ID
      (ADJUST FOR LBRACKET varList RBRACKET)?
      (USING estimator=ID)?
    ;

varList : ID (COMMA ID)* ;

// ─── Intervention ─────────────────────────────────────────────────────────────

interveneStmt
    : (FROM MODEL modelRef=ID)?
      INTERVENE ON intervention=assignment
      (COMPARE TO compareTo=assignment)?
      RETURN interveneReturn
    ;

assignment : var=ID EQ value=literal ;

interveneReturn
    : DISTRIBUTION OF var=ID   # distributionReturn
    | EFFECT ON var=ID          # effectReturn
    | EXPECTATION OF var=ID     # expectationReturn
    ;

// ─── Counterfactual ───────────────────────────────────────────────────────────

counterfactualStmt
    : COUNTERFACTUAL (FOR unit=ID LPAREN unitId=STRING RPAREN)?
      (GIVEN conditionList)?
      WHAT WOULD outcome=ID HAVE BEEN IF ifAssignment=assignment
    ;

conditionList : assignment (COMMA assignment)* ;

// ─── Context switching ──────────────────────────────────────────────────────────

// Switch the active graph to a named model (a base model or a derived
// intervention world, e.g.  USE MODEL UserRetentionModel_do_Treatment_1).
useModelStmt : USE MODEL name=ID ;

// Reset the active graph back to the base (original) model, discarding the
// active intervention world. Derived worlds remain registered and addressable.
resetStmt    : RESET ;

// ─── Inspection ───────────────────────────────────────────────────────────────

showDagStmt          : SHOW DAG name=ID? ;
describeVariableStmt : DESCRIBE VARIABLE name=ID ;

// ─── Sub-types ────────────────────────────────────────────────────────────────

variableType : BINARY | CONTINUOUS | CATEGORICAL | EVENT | LATENT ;

variableRole : TREATMENT | OUTCOME | COVARIATE | MEDIATOR_ROLE | COLLIDER_ROLE | CONFOUNDER_ROLE | UNKNOWN_ROLE ;

literal : NUMBER | STRING | BOOL ;

// ─── Lexer ────────────────────────────────────────────────────────────────────

// Statement keywords (uppercase)
DEFINE        : 'DEFINE' ;
CAUSAL        : 'CAUSAL' ;
VARIABLE      : 'VARIABLE' ;
MODEL         : 'MODEL' ;
DAG           : 'DAG' ;
FOR           : 'FOR' ;
UNIT          : 'UNIT' ;
OBSERVED      : 'OBSERVED' ;
AS            : 'AS' ;
TYPE          : 'TYPE' ;
ROLE          : 'ROLE' ;
CAUSES        : 'CAUSES' ;
EFFECTS       : 'EFFECTS' ;
ANCESTORS     : 'ANCESTORS' ;
DESCENDANTS   : 'DESCENDANTS' ;
MEDIATORS     : 'MEDIATORS' ;
COLLIDERS     : 'COLLIDERS' ;
CONFOUNDERS   : 'CONFOUNDERS' ;
BACKDOOR      : 'BACKDOOR' ;
PATHS         : 'PATHS' ;
BETWEEN       : 'BETWEEN' ;
AND           : 'AND' ;
OF            : 'OF' ;
ON            : 'ON' ;
IDENTIFY      : 'IDENTIFY' ;
EFFECT        : 'EFFECT' ;
RETURN        : 'RETURN' ;
ADJUSTMENT    : 'ADJUSTMENT' ;
SETS          : 'SETS' ;
EXPLAIN       : 'EXPLAIN' ;
ESTIMATE      : 'ESTIMATE' ;
ADJUST        : 'ADJUST' ;
USING         : 'USING' ;
INTERVENE     : 'INTERVENE' ;
COMPARE       : 'COMPARE' ;
TO            : 'TO' ;
FROM          : 'FROM' ;
DISTRIBUTION  : 'DISTRIBUTION' ;
EXPECTATION   : 'EXPECTATION' ;
COUNTERFACTUAL : 'COUNTERFACTUAL' ;
WHAT          : 'WHAT' ;
WOULD         : 'WOULD' ;
HAVE          : 'HAVE' ;
BEEN          : 'BEEN' ;
IF            : 'IF' ;
GIVEN         : 'GIVEN' ;
USE           : 'USE' ;
RESET         : 'RESET' ;
SHOW          : 'SHOW' ;
DESCRIBE      : 'DESCRIBE' ;

// Variable types (lowercase — do not conflict with mixed-case IDs)
BINARY        : 'binary' ;
CONTINUOUS    : 'continuous' ;
CATEGORICAL   : 'categorical' ;
EVENT         : 'event' ;
LATENT        : 'latent' ;

// Variable roles (lowercase)
TREATMENT     : 'treatment' ;
OUTCOME       : 'outcome' ;
COVARIATE     : 'covariate' ;
MEDIATOR_ROLE : 'mediator' ;
COLLIDER_ROLE : 'collider' ;
CONFOUNDER_ROLE : 'confounder' ;
UNKNOWN_ROLE  : 'unknown' ;

// Literals
BOOL          : 'true' | 'false' ;
NUMBER        : '-'? [0-9]+ ('.' [0-9]+)? ;
STRING        : '"' (~["\r\n] | '\\"')* '"' ;

// Symbols
ARROW         : '->' ;
LBRACE        : '{' ;
RBRACE        : '}' ;
LBRACKET      : '[' ;
RBRACKET      : ']' ;
LPAREN        : '(' ;
RPAREN        : ')' ;
COMMA         : ',' ;
EQ            : '=' ;

// Identifier — must come after all keyword tokens so keywords are not tokenised as IDs
ID            : [a-zA-Z_][a-zA-Z0-9_]* ;

// Ignored
WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
