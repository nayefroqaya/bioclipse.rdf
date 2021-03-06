/*
 * (c) Copyright 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.riot.lang;

import static com.hp.hpl.jena.riot.tokens.TokenType.* ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;
import atlas.event.Event ;
import atlas.event.EventManager ;
import atlas.lib.Sink ;

import com.hp.hpl.jena.datatypes.RDFDatatype ;
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype ;
import com.hp.hpl.jena.graph.Node ;
import com.hp.hpl.jena.graph.Triple ;
import com.hp.hpl.jena.iri.IRI ;
import com.hp.hpl.jena.riot.Checker ;
import com.hp.hpl.jena.riot.IRIResolver ;
import com.hp.hpl.jena.riot.PrefixMap ;
import com.hp.hpl.jena.riot.Prologue ;
import com.hp.hpl.jena.riot.RIOT ;
import com.hp.hpl.jena.riot.RiotException ;
import com.hp.hpl.jena.riot.tokens.Token ;
import com.hp.hpl.jena.riot.tokens.TokenType ;
import com.hp.hpl.jena.riot.tokens.Tokenizer ;
import com.hp.hpl.jena.sparql.core.NodeConst ;
import com.hp.hpl.jena.sparql.util.LabelToNodeMap ;
import com.hp.hpl.jena.tdb.lib.NodeFmtLib ;
import com.hp.hpl.jena.vocabulary.OWL ;

/** The main engine for all things Turtle-ish. */
public abstract class LangTurtleBase extends LangBase
{
    /* See http://www.w3.org/TeamSubmission/turtle/ */

    /*
[1]     turtleDoc       ::=     statement*
[2]     statement       ::=     directive '.' | triples '.' | ws+
[3]     directive       ::=     prefixID | base
[4]     prefixID        ::=     '@prefix' ws+ prefixName? ':' uriref
[5]     base            ::=     '@base'   ws+ uriref
[6]     triples         ::=     subject predicateObjectList
[7]     predicateObjectList     ::=     verb objectList ( ';' verb objectList )* ( ';')?
[8]     objectList      ::=     object ( ',' object)*
[9]     verb            ::=     predicate | 'a'
[10]    comment         ::=     '#' ( [^#xA#xD] )*
[11]    subject         ::=     resource | blank
[12]    predicate       ::=     resource
[13]    object          ::=     resource | blank | literal
[14]    literal         ::=     quotedString ( '@' language )? | datatypeString | integer | double | decimal | boolean
[15]    datatypeString  ::=     quotedString '^^' resource
[16]    integer         ::=     ('-' | '+')? [0-9]+
[17]    double          ::=     ('-' | '+')? ( [0-9]+ '.' [0-9]* exponent | '.' ([0-9])+ exponent | ([0-9])+ exponent )
[18]    decimal         ::=     ('-' | '+')? ( [0-9]+ '.' [0-9]* | '.' ([0-9])+ | ([0-9])+ )
[19]    exponent        ::=     [eE] ('-' | '+')? [0-9]+
[20]    boolean         ::=     'true' | 'false'
[21]    blank           ::=     nodeID | '[]' | '[' predicateObjectList ']' | collection
[22]    itemList        ::=     object+
[23]    collection      ::=     '(' itemList? ')'
[24]    ws              ::=     #x9 | #xA | #xD | #x20 | comment
[25]    resource        ::=     uriref | qname
[26]    nodeID          ::=     '_:' name
[27]    qname           ::=     prefixName? ':' name?
[28]    uriref          ::=     '<' relativeURI '>'
[29]    language        ::=     [a-z]+ ('-' [a-z0-9]+ )*
[30]    nameStartChar   ::=     [A-Z] | "_" | [a-z] | [#x00C0-#x00D6] | [#x00D8-#x00F6] | [#x00F8-#x02FF] | [#x0370-#x037D] | [#x037F-#x1FFF] | [#x200C-#x200D] | [#x2070-#x218F] | [#x2C00-#x2FEF] | [#x3001-#xD7FF] | [#xF900-#xFDCF] | [#xFDF0-#xFFFD] | [#x10000-#xEFFFF]
[31]    nameChar        ::=     nameStartChar | '-' | [0-9] | #x00B7 | [#x0300-#x036F] | [#x203F-#x2040]
[32]    name            ::=     nameStartChar nameChar*
[33]    prefixName      ::=     ( nameStartChar - '_' ) nameChar*
[34]    relativeURI     ::=     ucharacter*
[35]    quotedString    ::=     string | longString
[36]    string          ::=     #x22 scharacter* #x22
[37]    longString      ::=     #x22 #x22 #x22 lcharacter* #x22 #x22 #x22
[38]    character       ::=     '\' 'u' hex hex hex hex | '\' 'U' hex hex hex hex hex hex hex hex |
                                '\\' | [#x20-#x5B] | [#x5D-#x10FFFF]
[39]    echaracter      ::=     character | '\t' | '\n' | '\r'
[40]    hex             ::=     [#x30-#x39] | [#x41-#x46]
[41]    ucharacter      ::=     ( character - #x3E ) | '\>'
[42]    scharacter      ::=     ( echaracter - #x22 ) | '\"'
[43]    lcharacter      ::=     echaracter | '\"' | #x9 | #xA | #xD  
     */
    
    protected static final Logger log = LoggerFactory.getLogger("Turtle Parser") ;
    
    // Predicates
    protected final static String KW_A              = "a" ;
    protected final static String KW_SAME_AS        = "=" ;
    protected final static String KW_LOG_IMPLIES    = "=>" ;
    protected final static String KW_TRUE           = "true" ;
    protected final static String KW_FALSE          = "false" ;
    
    protected final static  boolean VERBOSE    = false ;
    protected static final boolean CHECKING   = true ;
    protected final boolean strict            = false ;
    
    protected final Prologue prologue ;
    
    /** Provide access to the prologue.  
     * Use with care.
     */
    public Prologue getPrologue()        { return prologue ; }

    /** Provide access to the prefix map.  
     * Note this parser uses a custom, lightweight prefix mapping implementation.
     * Use with care.
     */
    public PrefixMap getPrefixMap()        { return prologue.getPrefixMap() ; }
    
    private final Sink<Triple> sink ;
    
//    public LangTurtle(Tokenizer tokens)
//    {
//        this("http://example/", tokens, new PrintingSink(log)) ;
//    }
    
    public LangTurtleBase(String baseURI, Tokenizer tokens, Sink<Triple> sink)
    { 
        super(new Checker(null), null, tokens) ;
        this.sink = sink ;
        this.prologue = new Prologue(new PrefixMap(), new IRIResolver(baseURI)) ;
    }
    
    public final void parse()
    {
        EventManager.send(sink, new Event(RIOT.startRead, null)) ;
        while(moreTokens())
        {
            if ( lookingAt(DIRECTIVE) )
            {
                if ( VERBOSE ) log.info(">> directive") ;
                directive() ;
                if ( VERBOSE ) log.info("<< directive") ;
                continue ;
            }
            
            oneTopLevelElement() ;
            if ( lookingAt(EOF) )
                break ;
        }
        EventManager.send(sink, new Event(RIOT.finishRead, null)) ;
    }
    
    
    // Do one top level item for the language.
    protected abstract void oneTopLevelElement() ;

    protected final void directive()
    {
        // It's a directive ...
        String x = peekToken().getImage() ;
        nextToken() ;
        
        if ( x.equals("base") )
        {
            if ( VERBOSE ) log.info("@base") ;
            directiveBase() ;
            return ;
        }
        
        if ( x.equals("prefix") )
        {
            if ( VERBOSE ) log.info("@prefix") ;
            directivePrefix() ;
            return ;
            
        }
        exception("Unregcognized directive: %s", x) ;
    }
    
    protected final void directivePrefix()
    {
        // Raw - unresolved prefix name.
        if ( ! lookingAt(PREFIXED_NAME) )
            exception("@prefix requires a prefix (found '"+peekToken()+"')") ;
        if ( peekToken().getImage2().length() != 0 )
            exception("@prefix requires a prefix and no suffix (found '"+peekToken()+"')") ;
        String prefix = peekToken().getImage() ;
        nextToken() ;
        if ( ! lookingAt(IRI) )
            exception("@prefix requires an IRI (found '"+peekToken()+"')") ;
        String iriStr = peekToken().getImage() ;
        // CHECK
        IRI iri = prologue.getResolver().resolveSilent(iriStr) ;
        if ( getChecker() != null ) getChecker().checkIRI(iri) ;
        prologue.getPrefixMap().add(prefix, iri) ;
        nextToken() ;
        if ( VERBOSE ) log.info("@prefix "+prefix+":  "+iri.toString()) ;
        expect("Prefix directive not terminated by a dot", DOT) ;
    }

    protected final void directiveBase()
    {
        String baseStr = peekToken().getImage() ;
        // CHECK
        IRI baseIRI = prologue.getResolver().resolve(baseStr) ;
        if ( getChecker() != null ) getChecker().checkIRI(baseIRI) ;
        
        if ( VERBOSE ) log.info("@base <"+baseIRI+">") ;
        nextToken() ;
        
        expect("Base directive not terminated by a dot", DOT) ;
        prologue.setBaseURI(new IRIResolver(baseIRI)) ;
    }

    // Must be at least one triple. 
    protected final void triples()
    {
        // Lookin at a node.
        Node subject = node() ;
        if ( subject == null )
            exception("Not recognized: expected node: %s", peekToken().text()) ;
        
        nextToken() ;
        predicateObjectList(subject) ;
        expectEndOfTriples() ;
    }

    protected final void expectEndOfTriples()
    {
        // The DOT is required by Turtle (strictly).
        // It is not in N3 and SPARQL.
    
        if ( strict )
            expect("Triples not terminated by DOT", DOT) ;
        else
            expectOrEOF("Triples not terminated by DOT", DOT) ;
    }

    protected final void predicateObjectList(Node subject)
    {
        if ( VERBOSE ) log.info("predicateObjectList("+subject+")") ;
        predicateObjectItem(subject) ;

        for(;;)
        {
            if ( ! lookingAt(SEMICOLON) )
                break ;
            // list continues - move over the ";"
            nextToken() ;
            if ( ! peekPredicate() )
                // Trailing (pointless) SEMICOLON, no following predicate/object list.
                break ;
            predicateObjectItem(subject) ;
        }
    }

    protected final void predicateObjectItem(Node subject)
    {
        Node predicate = predicate() ;
        nextToken() ;
        objectList(subject, predicate) ;
    }
    
    static protected final Node nodeSameAs = OWL.sameAs.asNode() ; 
    static protected final Node nodeLogImplies = Node.createURI("http://www.w3.org/2000/10/swap/log#implies") ;
    
    /** Get predicate - maybe null for "illegal" */
    protected final Node predicate()
    {
        if ( lookingAt(TokenType.KEYWORD) )
        {
            String image = peekToken().getImage() ;
            if ( image.equals(KW_A) )
                return NodeConst.nodeRDFType ;
            if ( !strict && image.equals(KW_SAME_AS) )
                return nodeSameAs ;
            if ( !strict && image.equals(KW_LOG_IMPLIES) )
                return NodeConst.nodeRDFType ;
            exception("Unrecognized: "+image) ;
        }
            
        // Maybe null
        return node() ; 
    }

    /** Check raw token to see if it might be a predciate */
    protected final boolean peekPredicate()
    {
        if ( lookingAt(TokenType.KEYWORD) )
        {
            String image = peekToken().getImage() ;
            if ( image.equals(KW_A) )
                return true ;
            if ( !strict && image.equals(KW_SAME_AS) )
                return true ;
            if ( !strict && image.equals(KW_LOG_IMPLIES) )
                return true ;
            return false ; 
        }
//        if ( lookingAt(NODE) )
//            return true ; 
        if ( lookingAt(TokenType.IRI) )
            return true ;
        if ( lookingAt(TokenType.PREFIXED_NAME) )
            return true ;
        return false ;
    }
    
    protected final Node node()
    {
        // Token to Node
        Node n = tokenAsNode(peekToken()) ;
        if ( getChecker() != null )
            getChecker().check(n) ; 
        return n ;
    }
    
    protected final void objectList(Node subject, Node predicate)
    {
        if ( VERBOSE ) log.info("objectList("+subject+", "+predicate+")") ;
        for(;;)
        {
            Node object = triplesNode() ;
            emit(subject, predicate, object) ;

            if ( ! moreTokens() )
                break ;
            if ( ! lookingAt(COMMA) )
                break ;
            // list continues - move over the ","
            nextToken() ;
        }
    }

    // A structure of triples that itself generates a node.  [] and (). 
    
    protected final Node triplesNode()
    {
        if ( lookingAt(NODE) )
        {
            Node n = node() ;
            nextToken() ;
            return n ; 
        }

        // Special words.
        if ( lookingAt(TokenType.KEYWORD) )
        {
            // Location independent node words
            String image = peekToken().getImage() ;
            nextToken() ;
            if ( image.equals(KW_TRUE) )
                return NodeConst.nodeTrue ;
            if ( image.equals(KW_FALSE) )
                return NodeConst.nodeFalse ;
            exception("Unrecognized keyword: "+image) ; 
        }
        
        return triplesNodeCompound() ;
    }
        
    protected final boolean peekTriplesNodeCompound()
    {
        if ( lookingAt(LBRACKET) )
            return true ;
        if ( lookingAt(LBRACE) )
            return true ;
        if ( lookingAt(LPAREN) )
            return true ;
        return false ;
    }
    
    protected final Node triplesNodeCompound()
    {
        if ( lookingAt(LBRACKET) )
            return triplesBlankNode() ;
        if ( lookingAt(LBRACE) )
            return triplesFormula() ;
        if ( lookingAt(LPAREN) )
            return triplesList() ;
        exception("Unrecognized: "+peekToken()) ;
        return null ;
    }
    
    protected final Node triplesBlankNode()
    {
        nextToken() ;        // Skip [
        Node subject = Node.createAnon() ;

        if ( peekPredicate() )
            predicateObjectList(subject) ;

        expect("Triples not terminated properly in []-list", RBRACKET) ;
        // Exit: after the ]
        return subject ;
    }
    
    protected final Node triplesFormula()
    {
        exception("Not implemented") ;
        return null ;
    }
    
    protected final Node triplesList()
    {
        nextToken() ;
        Node lastCell = null ;
        Node listHead = null ;
        
        for ( ;; )
        {
            if ( eof() )
                exception ("Unterminated list") ;
            
            if ( lookingAt(RPAREN) ) 
            {
                nextToken(); 
                break ;
            }
            
            // The value.
            Node n = triplesNode() ;
            
            if ( n == null )
                exception("Malformed list") ;
            
            // Node for the list structre.
            Node nextCell = Node.createAnon() ;
            if ( listHead == null )
                listHead = nextCell ;
            if ( lastCell != null )
                emit(lastCell, NodeConst.nodeRest, nextCell) ;
            lastCell = nextCell ;
            
            emit(nextCell, NodeConst.nodeFirst, n) ;

            if ( ! moreTokens() )   // Error.
                break ;
        }
        // On exit, just after the RPARENS
        
        if ( lastCell == null )
            // Simple ()
            return NodeConst.nodeNil ;
        
        // Finish list.
        emit(lastCell, NodeConst.nodeRest, NodeConst.nodeNil) ;
        return listHead ;
    }
   
    protected final void emit(Node subject, Node predicate, Node object)
    {
        if ( CHECKING )
        {
            if ( subject == null || ( ! subject.isURI() && ! subject.isBlank() ) )
                exception("Subject is not a URI or blank node") ;
            if ( predicate == null || ( ! predicate.isURI() ) )
                exception("Predicate not a URI") ;
            if ( object == null || ( ! object.isURI() && ! object.isBlank() && ! object.isLiteral() ) )
                exception("Object is not a URI, blank node or literal") ;
        }
        Triple t = new Triple(subject, predicate, object) ;
        if ( VERBOSE ) 
            log.info(NodeFmtLib.str(t)) ;
        sink.send(new Triple(subject, predicate, object)) ;
    }
    
    LabelToNodeMap lmap = LabelToNodeMap.createBNodeMap() ;
    
    @Override
    protected final Node tokenAsNode(Token token) 
    {
        switch(token.getType())
        {
            // Assumes that bnode labels have been sorted out already.
            case BNODE : 
            {
                String label = token.getImage() ;
                // Fix up ":" and "-" to produce a N-Triples safe label? 
                Node n = lmap.asNode(label) ;
                return n ;
            }
            case IRI :
            {
                String resolvedIRI = prologue.getResolver().resolve(token.getImage()).toString() ;
                return Node.createURI(resolvedIRI) ;
            }
            case PREFIXED_NAME :
            {
                String prefix = token.getImage() ;
                String suffix   = token.getImage2() ;
                String expansion = prologue.getPrefixMap().expand(prefix, suffix) ;
                if ( expansion == null )
                    exceptionDirect("Undefined prefix: "+prefix, token.getLine(), token.getColumn()) ;
                return Node.createURI(expansion) ;
            }
            case DECIMAL :
                return Node.createLiteral(token.getImage(), null, XSDDatatype.XSDdecimal)  ; 
            case DOUBLE :
                return Node.createLiteral(token.getImage(), null, XSDDatatype.XSDdouble)  ;
            case INTEGER:
                return Node.createLiteral(token.getImage(), null, XSDDatatype.XSDinteger) ;
            case LITERAL_DT :
            {
                Node n = tokenAsNode(token.getSubToken()) ;
                if ( ! n.isURI() )
                    throw new RiotException("Invalid token: "+token) ;
                
                RDFDatatype dt =  Node.getType(n.getURI()) ;
                return Node.createLiteral(token.getImage(), null, dt)  ;
            }
            case LITERAL_LANG : 
                return Node.createLiteral(token.getImage(), token.getImage2(), null)  ;
                
            case STRING:                
            case STRING1:
            case STRING2:
            case LONG_STRING1:
            case LONG_STRING2:
                return Node.createLiteral(token.getImage()) ;
            
            default: break ;
        }
        return null ;
    }

        
//    private Token convert(Token token)
//    {
//        if ( token.hasType(PREFIXED_NAME) )
//        {
//            String prefix = token.getImage() ;
//            String suffix   = token.getImage2() ;
//            String expansion = prologue.getPrefixMap().expand(prefix, suffix) ;
//            if ( expansion == null )
//                exceptionDirect("Undefined prefix: "+prefix, token.getLine(), token.getColumn()) ;
//            token.setType(IRI) ;
//            token.setImage(expansion) ;
//            token.setImage2(null) ;
//        } 
//        else if ( token.hasType(IRI) )
//        {
//            token.setImage(prologue.getResolver().resolve(token.getImage()).toString()) ;
//        }
//        else if ( token.hasType(LITERAL_DT) )
//        {
//            Token t = token.getSubToken() ;
//            t = convert(t) ;
//            token.setSubToken(t) ;
//        }
//        else if ( token.hasType(BNODE) )
//        {
//            String label = token.getImage() ;
//            // Fix up ":" and "-" to produce a N-Triples safe label? 
//            Node n = lmap.asNode(label) ;
//            token.setImage(n.getBlankNodeLabel()) ;
//        }
//        return token ;
//    }
    
}

/*
 * (c) Copyright 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */