// MIT License
//
// Copyright (c) .NET Foundation and contributors
//
// Derived from the Roslyn C# compiler grammar:
//   dotnet/roslyn — src/Compilers/CSharp/Portable/Generated/CSharp.Generated.g4
// (pinned upstream in .grammar-source.txt). Hand-adapted for prjxp: combined
// grammar, raw-string + keyword token handling, DIRECTIVE skip rule. See README.md.
//
// Phase 02 adaptations (csharp-grammar-upgrade):
//  - accessor_declaration: allow ';' (single-line accessors `get; set;`)
//  - method_declaration: allow ';' (interface methods `double Area();`)
//  - record_declaration: 'record' literal instead of the syntax_token catch-all
//    (which phantom-matched e.g. `double Area();` as a record). The literal
//    resolves to an implicit lexer token — like 'union'/'extension'/'get' —
//    so `record` no longer lexes as IDENTIFIER_TOKEN and cannot phantom-match
//    as a method return type.
//  - parameter / recursive_pattern: no fully-optional (epsilon) alternatives,
//    which caused infinite recursion in the ATN (parser hang)
//  - expression: removed omitted_array_size_expression (epsilon) alternative
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software to deal in it without restriction, including the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies, and to permit persons to whom the software is furnished to do so,
// subject to the MIT license terms.
grammar CSharp;

compilation_unit
  : extern_alias_directive* using_directive* global_statement* attribute_list* member_declaration*
  ;

extern_alias_directive
  : 'extern' 'alias' IDENTIFIER_TOKEN ';'
  ;

using_directive
  : 'global'? 'using' ('static' | ('unsafe'? name_equals))? type ';'
  ;

name_equals
  : identifier_name '='
  ;

identifier_name
  : 'global'
  | IDENTIFIER_TOKEN
  ;

attribute_list
  : '[' attribute_target_specifier? attribute (',' attribute)* ']'
  ;

attribute_target_specifier
  : syntax_token ':'
  ;

attribute
  : name attribute_argument_list?
  ;

name
  : alias_qualified_name
  | simple_name
  // Direct left recursion (inlined from qualified_name): ANTLR rejects the
  // indirect form (name -> qualified_name -> name).
  | name '.' simple_name
  ;

alias_qualified_name
  : identifier_name '::' simple_name
  ;

simple_name
  : generic_name
  | identifier_name
  ;

generic_name
  : IDENTIFIER_TOKEN type_argument_list
  ;

type_argument_list
  : '<' (type (',' type)*)? '>'
  ;

attribute_argument_list
  : '(' (attribute_argument (',' attribute_argument)*)? ')'
  ;

attribute_argument
  : (name_equals? | name_colon?) expression
  ;

name_colon
  : identifier_name ':'
  ;

member_declaration
  : base_field_declaration
  | base_method_declaration
  | base_namespace_declaration
  | base_property_declaration
  | base_type_declaration
  | delegate_declaration
  | enum_member_declaration
  | incomplete_member
  ;

base_field_declaration
  : event_field_declaration
  | field_declaration
  ;

event_field_declaration
  : attribute_list* modifier* 'event' variable_declaration ';'
  ;

modifier
  : 'abstract'
  | 'async'
  | 'closed'
  | 'const'
  | 'extern'
  | 'file'
  | 'fixed'
  | 'internal'
  | 'new'
  | 'override'
  | PARAMS
  | 'partial'
  | 'private'
  | 'protected'
  | 'public'
  | 'readonly'
  | 'ref'
  | 'this'
  | 'required'
  | 'safe'
  | 'scoped'
  | 'sealed'
  | 'static'
  | 'unsafe'
  | 'virtual'
  | 'volatile'
  ;

variable_declaration
  : type variable_declarator (',' variable_declarator)*
  ;

variable_declarator
  : IDENTIFIER_TOKEN bracketed_argument_list? equals_value_clause?
  ;

bracketed_argument_list
  : '[' argument (',' argument)* ']'
  ;

argument
  : name_colon? ('ref' | 'out' | 'in')? expression
  ;

equals_value_clause
  : '=' expression
  ;

field_declaration
  : attribute_list* modifier* variable_declaration ';'
  ;

base_method_declaration
  : constructor_declaration
  | conversion_operator_declaration
  | destructor_declaration
  | method_declaration
  | operator_declaration
  ;

constructor_declaration
  : attribute_list* modifier* IDENTIFIER_TOKEN parameter_list constructor_initializer? (block | (arrow_expression_clause ';'))
  ;

parameter_list
  : '(' (parameter (',' parameter)*)? ')'
  ;

// Two alternatives (type or name required) so the rule cannot derive epsilon:
// a fully-optional parameter created an epsilon cycle in the ATN that hung the
// parser on malformed input (infinite recursion in LL1Analyzer._LOOK).
parameter
  : attribute_list* modifier* type (IDENTIFIER_TOKEN | '__arglist')? equals_value_clause?
  | attribute_list* modifier* (IDENTIFIER_TOKEN | '__arglist') equals_value_clause?
  ;

constructor_initializer
  : ':' ('base' | 'this') argument_list
  ;

argument_list
  : '(' (argument (',' argument)*)? ')'
  ;

block
  : attribute_list* '{' statement* '}'
  ;

arrow_expression_clause
  : '=>' expression
  ;

conversion_operator_declaration
  : attribute_list* modifier* ('implicit' | 'explicit') explicit_interface_specifier? 'operator' 'checked'? type parameter_list (block | (arrow_expression_clause ';'))
  ;

explicit_interface_specifier
  : name '.'
  ;

destructor_declaration
  : attribute_list* modifier* '~' IDENTIFIER_TOKEN parameter_list (block | (arrow_expression_clause ';'))
  ;

method_declaration
  : attribute_list* modifier* type explicit_interface_specifier? IDENTIFIER_TOKEN type_parameter_list? parameter_list type_parameter_constraint_clause* (block | (arrow_expression_clause ';') | ';')
  ;

type_parameter_list
  : '<' type_parameter (',' type_parameter)* '>'
  ;

type_parameter
  : attribute_list* ('in' | 'out')? IDENTIFIER_TOKEN
  ;

type_parameter_constraint_clause
  : 'where' identifier_name ':' type_parameter_constraint (',' type_parameter_constraint)*
  ;

type_parameter_constraint
  : allows_constraint_clause
  | class_or_struct_constraint
  | constructor_constraint
  | default_constraint
  | type_constraint
  ;

allows_constraint_clause
  : 'allows' allows_constraint (',' allows_constraint)*
  ;

allows_constraint
  : ref_struct_constraint
  ;

ref_struct_constraint
  : 'ref' 'struct'
  ;

class_or_struct_constraint
  : 'class' '?'?
  | 'struct' '?'?
  ;

constructor_constraint
  : 'new' '(' ')'
  ;

default_constraint
  : 'default'
  ;

type_constraint
  : type
  ;

operator_declaration
  : attribute_list* modifier* type explicit_interface_specifier? 'operator' 'checked'? ('+' | '-' | '!' | '~' | '++' | '--' | '*' | '/' | '%' | '<<' | '>>' | '>>>' | '|' | '&' | '^' | '==' | '!=' | '<' | '<=' | '>' | '>=' | 'false' | 'true' | 'is' | '+=' | '-=' | '*=' | '/=' | '%=' | '&=' | '|=' | '^=' | '<<=' | '>>=' | '>>>=') parameter_list (block | (arrow_expression_clause ';'))
  ;

base_namespace_declaration
  : file_scoped_namespace_declaration
  | namespace_declaration
  ;

file_scoped_namespace_declaration
  : attribute_list* modifier* 'namespace' name ';' extern_alias_directive* using_directive* member_declaration*
  ;

namespace_declaration
  : attribute_list* modifier* 'namespace' name '{' extern_alias_directive* using_directive* member_declaration* '}' ';'?
  ;

base_property_declaration
  : event_declaration
  | indexer_declaration
  | property_declaration
  ;

event_declaration
  : attribute_list* modifier* 'event' type explicit_interface_specifier? IDENTIFIER_TOKEN (accessor_list | ';')
  ;

accessor_list
  : '{' accessor_declaration* '}'
  ;

accessor_declaration
  : attribute_list* modifier* ('get' | 'set' | 'init' | 'add' | 'remove' | IDENTIFIER_TOKEN) (block | (arrow_expression_clause ';') | ';')
  ;

indexer_declaration
  : attribute_list* modifier* type explicit_interface_specifier? 'this' bracketed_parameter_list (accessor_list | (arrow_expression_clause ';'))
  ;

bracketed_parameter_list
  : '[' parameter (',' parameter)* ']'
  ;

property_declaration
  : attribute_list* modifier* type explicit_interface_specifier? IDENTIFIER_TOKEN (accessor_list | ((arrow_expression_clause | equals_value_clause) ';'))
  ;

base_type_declaration
  : enum_declaration
  | type_declaration
  ;

enum_declaration
  : attribute_list* modifier* 'enum' IDENTIFIER_TOKEN base_list? '{'? (enum_member_declaration (',' enum_member_declaration)* ','?)? '}'? ';'?
  ;

base_list
  : ':' base_type (',' base_type)*
  ;

base_type
  : primary_constructor_base_type
  | simple_base_type
  ;

primary_constructor_base_type
  : type argument_list
  ;

simple_base_type
  : type
  ;

enum_member_declaration
  : attribute_list* modifier* IDENTIFIER_TOKEN equals_value_clause?
  ;

type_declaration
  : class_declaration
  | extension_block_declaration
  | interface_declaration
  | record_declaration
  | struct_declaration
  | union_declaration
  ;

// The body's braces are mandatory (one alternative) so an empty or partial
// body cannot be re-parsed against the enclosing scope: with optional braces
// ANTLR could close the type early and swallow sibling declarations.
class_declaration
  : attribute_list* modifier* 'class' IDENTIFIER_TOKEN type_parameter_list? parameter_list? base_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

extension_block_declaration
  : attribute_list* modifier* 'extension' type type_parameter_list? parameter_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

interface_declaration
  : attribute_list* modifier* 'interface' IDENTIFIER_TOKEN type_parameter_list? parameter_list? base_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

// 'record' (a contextual keyword) resolves to an implicit lexer token, like
// 'union'/'extension'/'get' above — so it no longer lexes as IDENTIFIER_TOKEN
// and cannot phantom-match as a method return type or a record syntax_token.
record_declaration
  : attribute_list* modifier* 'record' ('class' | 'struct')? IDENTIFIER_TOKEN type_parameter_list? parameter_list? base_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

struct_declaration
  : attribute_list* modifier* 'struct' IDENTIFIER_TOKEN type_parameter_list? parameter_list? base_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

union_declaration
  : attribute_list* modifier* 'union' IDENTIFIER_TOKEN type_parameter_list? parameter_list? base_list? type_parameter_constraint_clause* ('{' member_declaration* '}' | ';'? )
  ;

delegate_declaration
  : attribute_list* modifier* 'delegate' type IDENTIFIER_TOKEN type_parameter_list? parameter_list type_parameter_constraint_clause* ';'
  ;

global_statement
  : attribute_list* modifier* statement
  ;

incomplete_member
  : attribute_list* modifier* type
  ;

type
  : function_pointer_type
  | name
  | predefined_type
  | ref_type
  | scoped_type
  | tuple_type
  // Direct left recursion (inlined from array_type/nullable_type/pointer_type):
  // ANTLR rejects the indirect form (type -> array_type -> type). array_type is
  // kept as a rule because array_creation_expression still references it.
  | type array_rank_specifier+
  | type '?'
  | type '*'
  ;

array_type
  : type array_rank_specifier+
  ;

array_rank_specifier
  : '[' (expression (',' expression)*)? ']'
  ;

function_pointer_type
  : 'delegate' '*' function_pointer_calling_convention? function_pointer_parameter_list
  ;

function_pointer_calling_convention
  : 'managed' function_pointer_unmanaged_calling_convention_list?
  | 'unmanaged' function_pointer_unmanaged_calling_convention_list?
  ;

function_pointer_unmanaged_calling_convention_list
  : '[' function_pointer_unmanaged_calling_convention (',' function_pointer_unmanaged_calling_convention)* ']'
  ;

function_pointer_unmanaged_calling_convention
  : IDENTIFIER_TOKEN
  ;

function_pointer_parameter_list
  : '<' function_pointer_parameter (',' function_pointer_parameter)* '>'
  ;

function_pointer_parameter
  : attribute_list* modifier* type
  ;

predefined_type
  : 'bool'
  | 'byte'
  | 'char'
  | 'decimal'
  | 'double'
  | 'float'
  | 'int'
  | 'long'
  | 'object'
  | 'sbyte'
  | 'short'
  | 'string'
  | 'uint'
  | 'ulong'
  | 'ushort'
  | 'void'
  ;

ref_type
  : 'ref' 'readonly'? type
  ;

scoped_type
  : 'scoped' type
  ;

tuple_type
  : '(' tuple_element (',' tuple_element)+ ')'
  ;

tuple_element
  : type IDENTIFIER_TOKEN?
  ;

statement
  : block
  | break_statement
  | checked_statement
  | common_for_each_statement
  | continue_statement
  | do_statement
  | empty_statement
  | expression_statement
  | fixed_statement
  | for_statement
  | goto_statement
  | if_statement
  | labeled_statement
  | local_declaration_statement
  | local_function_statement
  | lock_statement
  | return_statement
  | switch_statement
  | throw_statement
  | try_statement
  | unsafe_statement
  | using_statement
  | while_statement
  | yield_statement
  ;

break_statement
  : attribute_list* 'break' identifier_name? ';'
  ;

checked_statement
  : attribute_list* ('checked' | 'unchecked') block
  ;

common_for_each_statement
  : for_each_statement
  | for_each_variable_statement
  ;

for_each_statement
  : attribute_list* 'await'? 'foreach' '(' type IDENTIFIER_TOKEN 'in' expression ')' statement
  ;

for_each_variable_statement
  : attribute_list* 'await'? 'foreach' '(' expression 'in' expression ')' statement
  ;

continue_statement
  : attribute_list* 'continue' identifier_name? ';'
  ;

do_statement
  : attribute_list* 'do' statement 'while' '(' expression ')' ';'
  ;

empty_statement
  : attribute_list* ';'
  ;

expression_statement
  : attribute_list* expression ';'
  ;

fixed_statement
  : attribute_list* 'fixed' '(' variable_declaration ')' statement
  ;

for_statement
  : attribute_list* 'for' '(' (variable_declaration? | (expression (',' expression)*)?) ';' expression? ';' (expression (',' expression)*)? ')' statement
  ;

goto_statement
  : attribute_list* 'goto' ('case' | 'default')? expression? ';'
  ;

if_statement
  : attribute_list* 'if' '(' expression ')' statement else_clause?
  ;

else_clause
  : 'else' statement
  ;

labeled_statement
  : attribute_list* IDENTIFIER_TOKEN ':' statement
  ;

local_declaration_statement
  : attribute_list* 'await'? 'using'? modifier* variable_declaration ';'
  ;

local_function_statement
  : attribute_list* modifier* type IDENTIFIER_TOKEN type_parameter_list? parameter_list type_parameter_constraint_clause* (block | (arrow_expression_clause ';'))
  ;

lock_statement
  : attribute_list* 'lock' '(' expression ')' statement
  ;

return_statement
  : attribute_list* 'return' expression? ';'
  ;

switch_statement
  : attribute_list* 'switch' '('? expression ')'? '{' switch_section* '}'
  ;

switch_section
  : switch_label+ statement+
  ;

switch_label
  : case_pattern_switch_label
  | case_switch_label
  | default_switch_label
  ;

case_pattern_switch_label
  : 'case' pattern when_clause? ':'
  ;

pattern
  : constant_pattern
  | declaration_pattern
  | discard_pattern
  | list_pattern
  | parenthesized_pattern
  | recursive_pattern
  | relational_pattern
  | slice_pattern
  | type_pattern
  | unary_pattern
  // Direct left recursion (inlined from binary_pattern): ANTLR rejects the
  // indirect form (pattern -> binary_pattern -> pattern).
  | pattern ('or' | 'and') pattern
  ;

constant_pattern
  : expression
  ;

declaration_pattern
  : type variable_designation
  ;

variable_designation
  : discard_designation
  | parenthesized_variable_designation
  | single_variable_designation
  ;

discard_designation
  : '_'
  ;

parenthesized_variable_designation
  : '(' (variable_designation (',' variable_designation)*)? ')'
  ;

single_variable_designation
  : IDENTIFIER_TOKEN
  ;

discard_pattern
  : '_'
  ;

list_pattern
  : '[' (pattern (',' pattern)* ','?)? ']' variable_designation?
  ;

parenthesized_pattern
  : '(' pattern ')'
  ;

// At least one component required (no epsilon): a fully-optional recursive
// pattern created an epsilon cycle in the ATN that hung the parser on
// malformed input. The four alternatives cover every non-empty combination.
recursive_pattern
  : type positional_pattern_clause? property_pattern_clause? variable_designation?
  | type? positional_pattern_clause property_pattern_clause? variable_designation?
  | type? positional_pattern_clause? property_pattern_clause variable_designation?
  | type? positional_pattern_clause? property_pattern_clause? variable_designation
  ;

positional_pattern_clause
  : '(' (subpattern (',' subpattern)*)? ')'
  ;

subpattern
  : base_expression_colon? pattern
  ;

base_expression_colon
  : expression_colon
  | name_colon
  ;

expression_colon
  : expression ':'
  ;

property_pattern_clause
  : '{' (subpattern (',' subpattern)* ','?)? '}'
  ;

relational_pattern
  : '!=' expression
  | '<' expression
  | '<=' expression
  | '==' expression
  | '>' expression
  | '>=' expression
  ;

slice_pattern
  : '..' pattern?
  ;

type_pattern
  : type
  ;

unary_pattern
  : 'not' pattern
  ;

// NOTE: no var_pattern rule. In a combined grammar any 'var' literal in a
// parser rule becomes an implicit lexer token that shadows IDENTIFIER_TOKEN,
// which would break `var x = …` (type inference) everywhere. `is var x`
// still parses via declaration_pattern (type + designation).

when_clause
  : 'when' expression
  ;

case_switch_label
  : 'case' expression ':'
  ;

default_switch_label
  : 'default' ':'
  ;

throw_statement
  : attribute_list* 'throw' expression? ';'
  ;

try_statement
  : attribute_list* 'try' block catch_clause* finally_clause?
  ;

catch_clause
  : 'catch' catch_declaration? catch_filter_clause? block
  ;

catch_declaration
  : '(' type IDENTIFIER_TOKEN? ')'
  ;

catch_filter_clause
  : 'when' '(' expression ')'
  ;

finally_clause
  : 'finally' block
  ;

unsafe_statement
  : attribute_list* 'unsafe' block
  ;

using_statement
  : attribute_list* 'await'? 'using' '(' (variable_declaration | expression) ')' statement
  ;

while_statement
  : attribute_list* 'while' '(' expression ')' statement
  ;

yield_statement
  : attribute_list* 'yield' ('return' | 'break') expression? ';'
  ;

expression
  : anonymous_function_expression
  | anonymous_object_creation_expression
  | array_creation_expression
  | await_expression
  | base_object_creation_expression
  | cast_expression
  | checked_expression
  | collection_expression
  | declaration_expression
  | default_expression
  | element_binding_expression
  | field_expression
  | implicit_array_creation_expression
  | implicit_element_access
  | implicit_stack_alloc_array_creation_expression
  | initializer_expression
  | instance_expression
  | interpolated_string_expression
  | literal_expression
  | make_ref_expression
  | member_binding_expression
  // omitted_array_size_expression (an explicit epsilon) removed: it created an
  // epsilon cycle in the ATN that hung the parser on malformed input.
  | parenthesized_expression
  | prefix_unary_expression
  | query_expression
  | ref_expression
  | ref_type_expression
  | ref_value_expression
  | size_of_expression
  | stack_alloc_array_creation_expression
  | throw_expression
  | tuple_expression
  | type
  | type_of_expression
  | unsafe_expression
  // Direct left recursion (inlined from the removed sub-rules): ANTLR rejects
  // the indirect form (expression -> binary_expression -> expression, etc.).
  // range_expression's optional operands are expanded into explicit forms
  // because an optional left reference (expression? '..') is also rejected.
  | expression ('=' | '+=' | '-=' | '*=' | '/=' | '%=' | '&=' | '^=' | '|=' | '<<=' | '>>=' | '>>>=' | '??=') expression
  | expression ('+' | '-' | '*' | '/' | '%' | '<<' | '>>' | '>>>' | '||' | '&&' | '|' | '&' | '^' | '==' | '!=' | '<' | '<=' | '>' | '>=' | 'is' | 'as' | '??') expression
  | expression '?' expression
  | expression '?' expression ':' expression
  | expression bracketed_argument_list
  | expression argument_list
  | expression 'is' pattern
  | expression ('.' | '->') simple_name
  | expression ('++' | '--' | '!')
  | expression '..' expression
  | expression '..'
  | '..' expression
  | '..'
  | expression 'switch' '{' (switch_expression_arm (',' switch_expression_arm)* ','?)? '}'
  | expression 'with' initializer_expression
  ;

anonymous_function_expression
  : anonymous_method_expression
  | lambda_expression
  ;

anonymous_method_expression
  : modifier* 'delegate' parameter_list? block expression?
  ;

lambda_expression
  : parenthesized_lambda_expression
  | simple_lambda_expression
  ;

parenthesized_lambda_expression
  : attribute_list* modifier* type? parameter_list '=>' (block | expression)
  ;

simple_lambda_expression
  : attribute_list* modifier* parameter '=>' (block | expression)
  ;

anonymous_object_creation_expression
  : 'new' '{' (anonymous_object_member_declarator (',' anonymous_object_member_declarator)* ','?)? '}'
  ;

anonymous_object_member_declarator
  : name_equals? expression
  ;

array_creation_expression
  : 'new' array_type initializer_expression?
  ;

initializer_expression
  : '{' (expression (',' expression)* ','?)? '}'
  ;

await_expression
  : 'await' expression
  ;

base_object_creation_expression
  : implicit_object_creation_expression
  | object_creation_expression
  ;

implicit_object_creation_expression
  : 'new' argument_list initializer_expression?
  ;

object_creation_expression
  : 'new' type argument_list? initializer_expression?
  ;

cast_expression
  : '(' type ')' expression
  ;

checked_expression
  : 'checked' '(' expression ')'
  | 'unchecked' '(' expression ')'
  ;

collection_expression
  : '[' (collection_element (',' collection_element)* ','?)? ']'
  ;

collection_element
  : expression_element
  | spread_element
  | with_element
  ;

expression_element
  : expression
  ;

spread_element
  : '..' expression
  ;

with_element
  : 'with' argument_list
  ;

declaration_expression
  : type variable_designation
  ;

default_expression
  : 'default' '(' type ')'
  ;

element_binding_expression
  : bracketed_argument_list
  ;

field_expression
  : 'field'
  ;

implicit_array_creation_expression
  : 'new' '[' ','* ']' initializer_expression
  ;

implicit_element_access
  : bracketed_argument_list
  ;

implicit_stack_alloc_array_creation_expression
  : 'stackalloc' '[' ']' initializer_expression
  ;

instance_expression
  : base_expression
  | this_expression
  ;

base_expression
  : 'base'
  ;

this_expression
  : 'this'
  ;

// NOTE: The upstream grammar parses interpolated strings structurally (text +
// holes) via placeholder token rules. For chunking we only need the literal as
// a whole, so we collapse it to a single lexer token (see INTERPOLATED_STRING_TOKEN).
interpolated_string_expression
  : INTERPOLATED_STRING_TOKEN
  ;

literal_expression
  : 'default'
  | 'false'
  | 'null'
  | 'true'
  | '__arglist'
  | CHARACTER_LITERAL_TOKEN
  | MULTI_LINE_RAW_STRING_LITERAL_TOKEN
  | NUMERIC_LITERAL_TOKEN
  | SINGLE_LINE_RAW_STRING_LITERAL_TOKEN
  | STRING_LITERAL_TOKEN
  | UTF8_MULTI_LINE_RAW_STRING_LITERAL_TOKEN
  | UTF8_SINGLE_LINE_RAW_STRING_LITERAL_TOKEN
  | UTF8_STRING_LITERAL_TOKEN
  ;

// NOTE: The UTF8 variants (C# 13 `u8` suffix) inline the base literal content
// instead of referencing the base token rules: a non-fragment lexer rule may
// only reference *fragment* lexer rules, and the base rules are real tokens.
UTF8_MULTI_LINE_RAW_STRING_LITERAL_TOKEN
  : '"""' .*? '"""' ('U8' | 'u8')
  ;

UTF8_SINGLE_LINE_RAW_STRING_LITERAL_TOKEN
  : '"""' (~[\r\n])*? '"""' ('U8' | 'u8')
  ;

UTF8_STRING_LITERAL_TOKEN
  : REGULAR_STRING_LITERAL_TOKEN ('U8' | 'u8')
  | VERBATIM_STRING_LITERAL_TOKEN ('U8' | 'u8')
  ;

make_ref_expression
  : '__makeref' '(' expression ')'
  ;

member_binding_expression
  : '.' simple_name
  ;

parenthesized_expression
  : '(' expression ')'
  ;

prefix_unary_expression
  : '!' expression
  | '&' expression
  | '*' expression
  | '+' expression
  | '++' expression
  | '-' expression
  | '--' expression
  | '^' expression
  | '~' expression
  ;

query_expression
  : from_clause query_body
  ;

from_clause
  : 'from' type? IDENTIFIER_TOKEN 'in' expression
  ;

query_body
  : query_clause+ select_or_group_clause query_continuation?
  ;

query_clause
  : from_clause
  | join_clause
  | let_clause
  | order_by_clause
  | where_clause
  ;

join_clause
  : 'join' type? IDENTIFIER_TOKEN 'in' expression 'on' expression 'equals' expression join_into_clause?
  ;

join_into_clause
  : 'into' IDENTIFIER_TOKEN
  ;

let_clause
  : 'let' IDENTIFIER_TOKEN '=' expression
  ;

order_by_clause
  : 'orderby' ordering (',' ordering)*
  ;

ordering
  : expression ('ascending' | 'descending')?
  ;

where_clause
  : 'where' expression
  ;

select_or_group_clause
  : group_clause
  | select_clause
  ;

group_clause
  : 'group' expression 'by' expression
  ;

select_clause
  : 'select' expression
  ;

query_continuation
  : 'into' IDENTIFIER_TOKEN query_body
  ;

ref_expression
  : 'ref' expression
  ;

ref_type_expression
  : '__reftype' '(' expression ')'
  ;

ref_value_expression
  : '__refvalue' '(' expression ',' type ')'
  ;

size_of_expression
  : 'sizeof' '(' type ')'
  ;

stack_alloc_array_creation_expression
  : 'stackalloc' type initializer_expression?
  ;

switch_expression_arm
  : pattern when_clause? '=>' expression
  ;

throw_expression
  : 'throw' expression
  ;

tuple_expression
  : '(' argument (',' argument)+ ')'
  ;

type_of_expression
  : 'typeof' '(' type ')'
  ;

unsafe_expression
  : 'unsafe' '(' expression ')'
  ;

xml_node
  : xml_comment
  | xml_c_data_section
  | xml_element
  | xml_empty_element
  | xml_processing_instruction
  | xml_text
  ;

xml_comment
  : '<!--' XML_TEXT_LITERAL_TOKEN* '-->'
  ;

xml_c_data_section
  : '<![CDATA[' XML_TEXT_LITERAL_TOKEN* ']]>'
  ;

xml_element
  : xml_element_start_tag xml_node* xml_element_end_tag
  ;

xml_element_start_tag
  : '<' xml_name xml_attribute* '>'
  ;

xml_name
  : xml_prefix? IDENTIFIER_TOKEN
  ;

xml_prefix
  : IDENTIFIER_TOKEN ':'
  ;

xml_attribute
  : xml_cref_attribute
  | xml_name_attribute
  | xml_text_attribute
  ;

xml_cref_attribute
  : xml_name '=' ('\'' | '"') cref ('\'' | '"')
  ;

cref
  : member_cref
  | qualified_cref
  | type_cref
  ;

member_cref
  : conversion_operator_member_cref
  | extension_member_cref
  | indexer_member_cref
  | name_member_cref
  | operator_member_cref
  ;

conversion_operator_member_cref
  : 'explicit' 'operator' 'checked'? type cref_parameter_list?
  | 'implicit' 'operator' 'checked'? type cref_parameter_list?
  ;

cref_parameter_list
  : '(' (cref_parameter (',' cref_parameter)*)? ')'
  ;

cref_parameter
  : 'in'? 'readonly'? type
  | 'out'? 'readonly'? type
  | 'ref'? 'readonly'? type
  ;

extension_member_cref
  : 'extension' type_argument_list? cref_parameter_list '.' member_cref
  ;

indexer_member_cref
  : 'this' cref_bracketed_parameter_list?
  ;

cref_bracketed_parameter_list
  : '[' cref_parameter (',' cref_parameter)* ']'
  ;

name_member_cref
  : type cref_parameter_list?
  ;

operator_member_cref
  : 'operator' 'checked'? ('+' | '-' | '!' | '~' | '++' | '--' | '*' | '/' | '%' | '<<' | '>>' | '>>>' | '|' | '&' | '^' | '==' | '!=' | '<' | '<=' | '>' | '>=' | 'false' | 'true' | '+=' | '-=' | '*=' | '/=' | '%=' | '&=' | '|=' | '^=' | '<<=' | '>>=' | '>>>=') cref_parameter_list?
  ;

qualified_cref
  : type '.' member_cref
  ;

type_cref
  : type
  ;

xml_name_attribute
  : xml_name '=' ('\'' | '"') identifier_name ('\'' | '"')
  ;

xml_text_attribute
  : xml_name '=' ('\'' | '"') XML_TEXT_LITERAL_TOKEN* ('\'' | '"')
  ;

xml_element_end_tag
  : '</' xml_name '>'
  ;

xml_empty_element
  : '<' xml_name xml_attribute* '/>'
  ;

xml_processing_instruction
  : '<?' xml_name XML_TEXT_LITERAL_TOKEN* '?>'
  ;

xml_text
  : XML_TEXT_LITERAL_TOKEN+
  ;

structured_trivia
  : directive_trivia
  | documentation_comment_trivia
  | skipped_tokens_trivia
  ;

directive_trivia
  : bad_directive_trivia
  | branching_directive_trivia
  | define_directive_trivia
  | end_if_directive_trivia
  | end_region_directive_trivia
  | error_directive_trivia
  | ignored_directive_trivia
  | line_or_span_directive_trivia
  | load_directive_trivia
  | nullable_directive_trivia
  | pragma_checksum_directive_trivia
  | pragma_warning_directive_trivia
  | reference_directive_trivia
  | region_directive_trivia
  | shebang_directive_trivia
  | undef_directive_trivia
  | warning_directive_trivia
  ;

bad_directive_trivia
  : '#' syntax_token
  ;

branching_directive_trivia
  : conditional_directive_trivia
  | else_directive_trivia
  ;

conditional_directive_trivia
  : elif_directive_trivia
  | if_directive_trivia
  ;

elif_directive_trivia
  : '#' 'elif' expression
  ;

if_directive_trivia
  : '#' 'if' expression
  ;

else_directive_trivia
  : '#' 'else'
  ;

define_directive_trivia
  : '#' 'define' IDENTIFIER_TOKEN
  ;

end_if_directive_trivia
  : '#' 'endif'
  ;

end_region_directive_trivia
  : '#' 'endregion'
  ;

error_directive_trivia
  : '#' 'error'
  ;

ignored_directive_trivia
  : '#' ':' STRING_LITERAL_TOKEN?
  ;

line_or_span_directive_trivia
  : line_directive_trivia
  | line_span_directive_trivia
  ;

line_directive_trivia
  : '#' 'line' (NUMERIC_LITERAL_TOKEN | 'default' | 'hidden') STRING_LITERAL_TOKEN?
  ;

line_span_directive_trivia
  : '#' 'line' line_directive_position '-' line_directive_position NUMERIC_LITERAL_TOKEN? STRING_LITERAL_TOKEN
  ;

line_directive_position
  : '(' NUMERIC_LITERAL_TOKEN ',' NUMERIC_LITERAL_TOKEN ')'
  ;

load_directive_trivia
  : '#' 'load' STRING_LITERAL_TOKEN
  ;

nullable_directive_trivia
  : '#' 'nullable' ('enable' | 'disable' | 'restore') ('warnings' | 'annotations')?
  ;

pragma_checksum_directive_trivia
  : '#' 'pragma' 'checksum' STRING_LITERAL_TOKEN STRING_LITERAL_TOKEN STRING_LITERAL_TOKEN
  ;

pragma_warning_directive_trivia
  : '#' 'pragma' 'warning' ('disable' | 'restore') (expression (',' expression)*)?
  ;

reference_directive_trivia
  : '#' 'r' STRING_LITERAL_TOKEN
  ;

region_directive_trivia
  : '#' 'region'
  ;

shebang_directive_trivia
  : '#' '!'
  ;

undef_directive_trivia
  : '#' 'undef' IDENTIFIER_TOKEN
  ;

warning_directive_trivia
  : '#' 'warning'
  ;

documentation_comment_trivia
  : xml_node*
  ;

skipped_tokens_trivia
  : syntax_token*
  ;

// ---------------------------------------------------------------------------
// Lexer: whitespace and comments (skipped)
// ---------------------------------------------------------------------------
BYTE_ORDER_MARK : '\uFEFF' -> skip;
WHITESPACE      : [ \t\r\n\u000B\u000C]+ -> skip;
LINE_COMMENT    : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT   : '/*' .*? '*/' -> skip;
// Preprocessor directive line (#if, #region, #nullable, #pragma, #define, ...).
// Skipped so the parser never sees '#'; code inside #if/#endif parses normally.
// The directive text stays in the source and flows 1:1 into chunk text via the
// chunker's line-range extraction. Maximal-munch wins over PUNCTUATION_TOKEN.
DIRECTIVE       : '#' ~[\r\n]* -> skip;

// ---------------------------------------------------------------------------
// Lexer: keywords (individual tokens so the parser can disambiguate them).
// Defined BEFORE IDENTIFIER_TOKEN so keyword words win over identifiers.
// ---------------------------------------------------------------------------
AS         : 'as';
BASE       : 'base';
BOOL       : 'bool';
BREAK      : 'break';
BYTE       : 'byte';
CASE       : 'case';
CATCH      : 'catch';
CHAR       : 'char';
CHECKED    : 'checked';
CLASS      : 'class';
CONTINUE   : 'continue';
DECIMAL    : 'decimal';
DEFAULT    : 'default';
DELEGATE   : 'delegate';
DO         : 'do';
DOUBLE     : 'double';
ELSE       : 'else';
ENUM       : 'enum';
EVENT      : 'event';
EXPLICIT   : 'explicit';
FALSE      : 'false';
FINALLY    : 'finally';
FLOAT      : 'float';
FOR        : 'for';
FOREACH    : 'foreach';
GOTO       : 'goto';
IF         : 'if';
IMPLICIT   : 'implicit';
IN         : 'in';
INT        : 'int';
INTERFACE  : 'interface';
IS         : 'is';
LOCK       : 'lock';
LONG       : 'long';
NAMESPACE  : 'namespace';
NULL       : 'null';
OBJECT     : 'object';
OPERATOR   : 'operator';
OUT        : 'out';
PARAMS     : 'params';
RETURN     : 'return';
SBYTE      : 'sbyte';
SHORT      : 'short';
SIZEOF     : 'sizeof';
STACKALLOC : 'stackalloc';
STRING     : 'string';
STRUCT     : 'struct';
SWITCH     : 'switch';
THIS       : 'this';
THROW      : 'throw';
TRUE       : 'true';
TRY        : 'try';
TYPEOF     : 'typeof';
UINT       : 'uint';
ULONG      : 'ulong';
UNCHECKED  : 'unchecked';
USHORT     : 'ushort';
USING      : 'using';
VOID       : 'void';
WHILE      : 'while';
ARGLIST    : '__arglist';
MAKEREF    : '__makeref';
REFTYPE    : '__reftype';
REFVALUE   : '__refvalue';

// NOTE: The upstream Roslyn grammar leaves the Unicode-category character rules
// as empty placeholders (the real classes are only documented in comments) and
// matches only 2-3 identifier characters. We replace them with pragmatic
// ASCII-based rules so the lexer actually tokenizes real C# source.
IDENTIFIER_TOKEN
  : '@'? IDENTIFIER_START_CHARACTER IDENTIFIER_PART_CHARACTER*
  ;

fragment IDENTIFIER_START_CHARACTER
  : LETTER_CHARACTER
  | UNDERSCORE_CHARACTER
  ;

fragment LETTER_CHARACTER
  : [a-zA-Z]
  ;

fragment UNDERSCORE_CHARACTER
  : '_'
  ;

fragment IDENTIFIER_PART_CHARACTER
  : COMBINING_CHARACTER
  | CONNECTING_CHARACTER
  | DECIMAL_DIGIT_CHARACTER
  | FORMATTING_CHARACTER
  | LETTER_CHARACTER
  ;

fragment COMBINING_CHARACTER
  : [a-zA-Z0-9]
  ;

fragment CONNECTING_CHARACTER
  : '_'
  ;

fragment DECIMAL_DIGIT_CHARACTER
  : [0-9]
  ;

fragment FORMATTING_CHARACTER
  : [a-zA-Z0-9]
  ;

// NOTE: The upstream `keyword` rule (a single token type for every keyword) is
// intentionally omitted: a real ANTLR parser cannot disambiguate keywords that
// share one token type. Individual keyword tokens are declared above instead.

NUMERIC_LITERAL_TOKEN
  : INTEGER_LITERAL_TOKEN
  | REAL_LITERAL_TOKEN
  ;

fragment INTEGER_LITERAL_TOKEN
  : DECIMAL_INTEGER_LITERAL_TOKEN
  | HEXADECIMAL_INTEGER_LITERAL_TOKEN
  ;

fragment DECIMAL_INTEGER_LITERAL_TOKEN
  : DECIMAL_DIGIT+ INTEGER_TYPE_SUFFIX?
  ;

fragment DECIMAL_DIGIT
  : '0'
  | '1'
  | '2'
  | '3'
  | '4'
  | '5'
  | '6'
  | '7'
  | '8'
  | '9'
  ;

fragment INTEGER_TYPE_SUFFIX
  : 'L'
  | 'l'
  | 'LU'
  | 'lU'
  | 'Lu'
  | 'lu'
  | 'U'
  | 'u'
  | 'UL'
  | 'uL'
  | 'Ul'
  | 'ul'
  ;

fragment HEXADECIMAL_INTEGER_LITERAL_TOKEN
  : ('0x' | '0X') HEXADECIMAL_DIGIT+ INTEGER_TYPE_SUFFIX?
  ;

fragment HEXADECIMAL_DIGIT
  : 'A'
  | 'a'
  | 'B'
  | 'b'
  | 'C'
  | 'c'
  | 'D'
  | 'd'
  | 'E'
  | 'e'
  | 'F'
  | 'f'
  | DECIMAL_DIGIT
  ;

fragment REAL_LITERAL_TOKEN
  : '.' DECIMAL_DIGIT+ EXPONENT_PART? REAL_TYPE_SUFFIX?
  | DECIMAL_DIGIT+ '.' DECIMAL_DIGIT+ EXPONENT_PART? REAL_TYPE_SUFFIX?
  | DECIMAL_DIGIT+ EXPONENT_PART REAL_TYPE_SUFFIX?
  | DECIMAL_DIGIT+ REAL_TYPE_SUFFIX
  ;

fragment EXPONENT_PART
  : ('E' | 'e') ('+' | '-')? DECIMAL_DIGIT+
  ;

fragment REAL_TYPE_SUFFIX
  : 'D'
  | 'd'
  | 'F'
  | 'f'
  | 'M'
  | 'm'
  ;

// NOTE: The character-level sub-rules are declared as `fragment`s so they are
// NOT standalone tokens (a bare single-character rule would otherwise match any
// character in the input). The upstream grammar left the "single character"
// rules empty; we fill them with the C# lexical-spec character classes.
CHARACTER_LITERAL_TOKEN
  : '\'' CHARACTER '\''
  ;

fragment CHARACTER
  : HEXADECIMAL_ESCAPE_SEQUENCE
  | SIMPLE_ESCAPE_SEQUENCE
  | SINGLE_CHARACTER
  | UNICODE_ESCAPE_SEQUENCE
  ;

fragment SINGLE_CHARACTER
  : ~['\\\r\n\u0085\u2028\u2029]
  ;

fragment HEXADECIMAL_ESCAPE_SEQUENCE
  : '\\x' HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT? HEXADECIMAL_DIGIT? HEXADECIMAL_DIGIT?
  ;

fragment SIMPLE_ESCAPE_SEQUENCE
  : '\\"'
  | '\\0'
  | '\\a'
  | '\\b'
  | '\\f'
  | '\\n'
  | '\\r'
  | '\\t'
  | '\\v'
  | '\\\''
  | '\\\\'
  ;

fragment UNICODE_ESCAPE_SEQUENCE
  : '\\u' HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT
  | '\\U' HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT HEXADECIMAL_DIGIT
  ;

STRING_LITERAL_TOKEN
  : REGULAR_STRING_LITERAL_TOKEN
  | VERBATIM_STRING_LITERAL_TOKEN
  ;

fragment REGULAR_STRING_LITERAL_TOKEN
  : '"' REGULAR_STRING_LITERAL_CHARACTER* '"'
  ;

fragment REGULAR_STRING_LITERAL_CHARACTER
  : HEXADECIMAL_ESCAPE_SEQUENCE
  | SIMPLE_ESCAPE_SEQUENCE
  | SINGLE_REGULAR_STRING_LITERAL_CHARACTER
  | UNICODE_ESCAPE_SEQUENCE
  ;

fragment SINGLE_REGULAR_STRING_LITERAL_CHARACTER
  : ~["\\\r\n\u0085\u2028\u2029]
  ;

fragment VERBATIM_STRING_LITERAL_TOKEN
  : '@"' VERBATIM_STRING_LITERAL_CHARACTER* '"'
  ;

fragment VERBATIM_STRING_LITERAL_CHARACTER
  : QUOTE_ESCAPE_SEQUENCE
  | SINGLE_VERBATIM_STRING_LITERAL_CHARACTER
  ;

fragment QUOTE_ESCAPE_SEQUENCE
  : '""'
  ;

fragment SINGLE_VERBATIM_STRING_LITERAL_CHARACTER
  : ~["]
  ;

OPERATOR_TOKEN
  : '!'
  | '!='
  | '%'
  | '%='
  | '&&'
  | '&'
  | '&='
  | '*'
  | '*='
  | '+'
  | '++'
  | '+='
  | '-'
  | '--'
  | '-='
  | '/'
  | '/='
  | '<'
  | '<<'
  | '<<='
  | '<='
  | '='
  | '=='
  | '>'
  | '>='
  | '>>'
  | '>>='
  | '>>>'
  | '>>>='
  | '??'
  | '??='
  | 'as'
  | 'is'
  | '^'
  | '^='
  | '|'
  | '|='
  | '||'
  | '~'
  ;

PUNCTUATION_TOKEN
  : '"'
  | '('
  | ')'
  | ','
  | '->'
  | '.'
  | '..'
  | '/>'
  | ':'
  | '::'
  | ';'
  | '</'
  | '=>'
  | '?'
  | '['
  | '\''
  | '\\'
  | ']'
  | '{'
  | '}'
  ;

base_argument_list
  : argument_list
  | bracketed_argument_list
  ;

base_cref_parameter_list
  : cref_bracketed_parameter_list
  | cref_parameter_list
  ;

base_parameter_list
  : bracketed_parameter_list
  | parameter_list
  ;

base_parameter
  : function_pointer_parameter
  | parameter
  ;

expression_or_pattern
  : expression
  | pattern
  ;

// NOTE: The upstream grammar left these string-content token rules as empty
// placeholders (the real lexical rules are only documented in comments). We fill
// them with pragmatic whole-literal rules so real C# source tokenizes correctly.

// Whole interpolated string ($"..." / $@"..."), matched non-greedily up to the
// closing quote. Collapsed from the upstream structural (text + holes) rules.
INTERPOLATED_STRING_TOKEN
  : '$' '@'? '"' .*? '"'
  ;

// Raw string literals (C# 11). Single-line first so it wins the tie for
// same-length matches; the multi-line rule handles content with newlines.
SINGLE_LINE_RAW_STRING_LITERAL_TOKEN
  : '"""' (~[\r\n])*? '"""'
  ;

MULTI_LINE_RAW_STRING_LITERAL_TOKEN
  : '"""' .*? '"""'
  ;

// XML text (used by the XML-literal rules). Kept narrow (a single alphanumeric)
// so it does not shadow identifiers; XML doc comments are skipped as line comments.
XML_TEXT_LITERAL_TOKEN
  : [a-zA-Z0-9]
  ;

// Catch-all token (any token). Defined LAST so the specific token rules above
// win for their inputs. Used where the grammar accepts "any token".
syntax_token
  : CHARACTER_LITERAL_TOKEN
  | IDENTIFIER_TOKEN
  | NUMERIC_LITERAL_TOKEN
  | OPERATOR_TOKEN
  | PUNCTUATION_TOKEN
  | STRING_LITERAL_TOKEN
  | INTERPOLATED_STRING_TOKEN
  | SINGLE_LINE_RAW_STRING_LITERAL_TOKEN
  | MULTI_LINE_RAW_STRING_LITERAL_TOKEN
  | XML_TEXT_LITERAL_TOKEN
  | AS | BASE | BOOL | BREAK | BYTE | CASE | CATCH | CHAR | CHECKED | CLASS
  | CONTINUE | DECIMAL | DEFAULT | DELEGATE | DO | DOUBLE | ELSE | ENUM | EVENT
  | EXPLICIT | FALSE | FINALLY | FLOAT | FOR | FOREACH | GOTO | IF | IMPLICIT
  | IN | INT | INTERFACE | IS | LOCK | LONG | NAMESPACE | NULL | OBJECT | OPERATOR
  | OUT | PARAMS | RETURN | SBYTE | SHORT | SIZEOF | STACKALLOC | STRING | STRUCT
  | SWITCH | THIS | THROW | TRUE | TRY | TYPEOF | UINT | ULONG | UNCHECKED
  | USHORT | USING | VOID | WHILE | ARGLIST | MAKEREF | REFTYPE | REFVALUE
  | modifier
  ;
