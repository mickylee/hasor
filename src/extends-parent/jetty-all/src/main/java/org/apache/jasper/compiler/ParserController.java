/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jasper.compiler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Stack;
import java.util.jar.JarFile;

import org.apache.jasper.JasperException;
import org.apache.jasper.JspCompilationContext;
import org.apache.jasper.xmlparser.XMLEncodingDetector;
import org.xml.sax.Attributes;

/**
 * Controller for the parsing of a JSP page.
 * <p>
 * The same ParserController instance is used for a JSP page and any JSP
 * segments included by it (via an include directive), where each segment may
 * be provided in standard or XML syntax. This class selects and invokes the
 * appropriate parser for the JSP page and its included segments.
 *
 * @author Pierre Delisle
 * @author Jan Luehe
 */
class ParserController implements TagConstants {

    private static final String CHARSET = "charset=";

    private JspCompilationContext ctxt;
    private Compiler compiler;
    private ErrorDispatcher err;

    /*
     * Indicates the syntax (XML or standard) of the file being processed
     */
    private boolean isXml;

    /*
     * A stack to keep track of the 'current base directory'
     * for include directives that refer to relative paths.
     */
    private Stack<String> baseDirStack = new Stack<String>();
    
    private boolean isEncodingSpecifiedInProlog;

    private boolean hasBom;

    private String sourceEnc;

    private boolean isDefaultPageEncoding;
    private boolean isTagFile;
    private boolean directiveOnly;

    /*
     * Constructor
     */
    public ParserController(JspCompilationContext ctxt, Compiler compiler) {
        this.ctxt = ctxt; 
	this.compiler = compiler;
	this.err = compiler.getErrorDispatcher();
    }

    public JspCompilationContext getJspCompilationContext () {
	return ctxt;
    }

    public Compiler getCompiler () {
	return compiler;
    }

    /**
     * Parses a JSP page or tag file. This is invoked by the compiler.
     *
     * @param inFileName The path to the JSP page or tag file to be parsed.
     */
    public Node.Nodes parse(String inFileName)
	        throws FileNotFoundException, JasperException, IOException {
	// If we're parsing a packaged tag file or a resource included by it
	// (using an include directive), ctxt.getTagFileJar() returns the 
	// JAR file from which to read the tag file or included resource,
	// respectively.
        isTagFile = ctxt.isTagFile();
        directiveOnly = false;
        return doParse(inFileName, null, ctxt.getTagFileJarUrl());
    }

    /**
     * Processes an include directive with the given path.
     *
     * @param inFileName The path to the resource to be included.
     * @param parent The parent node of the include directive.
     * @param jarFile The JAR file from which to read the included resource,
     * or null of the included resource is to be read from the filesystem
     */
    public Node.Nodes parse(String inFileName, Node parent,
			    URL jarFileUrl)
	        throws FileNotFoundException, JasperException, IOException {
        // For files that are statically included, isTagfile and directiveOnly
        // remain unchanged.
        return doParse(inFileName, parent, jarFileUrl);
    }

    /**
     * Extracts tag file directive information from the tag file with the
     * given name.
     *
     * This is invoked by the compiler 
     *
     * @param inFileName The name of the tag file to be parsed.
     */
    public Node.Nodes parseTagFileDirectives(String inFileName)
	        throws FileNotFoundException, JasperException, IOException {
        boolean isTagFileSave = isTagFile;
        boolean directiveOnlySave = directiveOnly;
        isTagFile = true;
        directiveOnly = true;
        Node.Nodes page = doParse(inFileName, null,
                             (URL) ctxt.getTagFileJarUrls().get(inFileName));
        directiveOnly = directiveOnlySave;
        isTagFile = isTagFileSave;
        return page;
    }

    /**
     * Parses the JSP page or tag file with the given path name.
     *
     * @param inFileName The name of the JSP page or tag file to be parsed.
     * @param parent The parent node (non-null when processing an include
     * directive)
     * @param isTagFile true if file to be parsed is tag file, and false if it
     * is a regular JSP page
     * @param directivesOnly true if the file to be parsed is a tag file and
     * we are only interested in the directives needed for constructing a
     * TagFileInfo.
     * @param jarFile The JAR file from which to read the JSP page or tag file,
     * or null if the JSP page or tag file is to be read from the filesystem
     */
    private Node.Nodes doParse(String inFileName,
                               Node parent,
                               URL jarFileUrl)
	        throws FileNotFoundException, JasperException, IOException {

	Node.Nodes parsedPage = null;
	isEncodingSpecifiedInProlog = false;
	isDefaultPageEncoding = false;
        hasBom = false;

	JarFile jarFile = getJarFile(jarFileUrl);
	String absFileName = resolveFileName(inFileName);
	String jspConfigPageEnc = getJspConfigPageEncoding(absFileName);

	// Figure out what type of JSP document and encoding type we are
	// dealing with
	determineSyntaxAndEncoding(absFileName, jarFile, jspConfigPageEnc);

	if (parent != null) {
	    // Included resource, add to dependent list
	    compiler.getPageInfo().addDependant(absFileName);
	}

        comparePageEncodings(jspConfigPageEnc);

	// Dispatch to the appropriate parser
	if (isXml) {
	    // JSP document (XML syntax)
            // InputStream for jspx page is created and properly closed in
            // JspDocumentParser.
            parsedPage = JspDocumentParser.parse(this, absFileName,
                                                 jarFile, parent,
                                                 isTagFile, directiveOnly,
                                                 sourceEnc,
                                                 jspConfigPageEnc,
                                                 isEncodingSpecifiedInProlog);
	} else {
	    // Standard syntax
	    InputStreamReader inStreamReader = null;
	    try {
		inStreamReader = JspUtil.getReader(absFileName, sourceEnc,
						   jarFile, ctxt, err);
		JspReader jspReader = new JspReader(ctxt, absFileName,
						    sourceEnc, inStreamReader,
						    err);
                parsedPage = Parser.parse(this, absFileName, jspReader, parent,
                                          isTagFile, directiveOnly, jarFileUrl,
					  sourceEnc, jspConfigPageEnc,
					  isDefaultPageEncoding, hasBom);
            } finally {
		if (inStreamReader != null) {
		    try {
			inStreamReader.close();
		    } catch (Exception any) {
		    }
		}
	    }
	}

	if (jarFile != null) {
	    try {
		jarFile.close();
	    } catch (Throwable t) {}
	}

	baseDirStack.pop();

	return parsedPage;
    }

    /*
     * Ensures that the page encoding specified in the JSP config element
     * (with matching URL pattern), if present, matches the page encoding
     * specified in the XML prolog of a JSP document (XML syntax) and the
     * page encoding derived from the BOM.
     *
     * @param jspConfigPageEnc Page encoding specified in JSP config element
     *
     * @throws JasperException if page encoding mismatch
     */
    private void comparePageEncodings(String jspConfigPageEnc)
            throws JasperException {

        if (jspConfigPageEnc == null) {
            return;
        }

        if (isXml && isEncodingSpecifiedInProlog) {
            /*
             * Make sure the encoding specified in the XML prolog matches
             * that in the JSP config element, treating "UTF-16", "UTF-16BE",
             * and "UTF-16LE" as identical.
             */
            if (!jspConfigPageEnc.equalsIgnoreCase(sourceEnc)
                    && (!jspConfigPageEnc.toLowerCase().startsWith("utf-16")
                        || !sourceEnc.toLowerCase().startsWith("utf-16"))) {
                err.jspError("jsp.error.prolog_config_encoding_mismatch",
                             sourceEnc, jspConfigPageEnc);
            }
        }

        if (hasBom) {
            /*
             * Make sure the encoding specified in the BOM matches
             * that in the JSP config element, treating "UTF-16", "UTF-16BE",
             * and "UTF-16LE" as identical.
             */
            if (!jspConfigPageEnc.equalsIgnoreCase(sourceEnc)
                    && (!jspConfigPageEnc.toLowerCase().startsWith("utf-16")
                        || !sourceEnc.toLowerCase().startsWith("utf-16"))) {
                err.jspError("jsp.error.bom_config_encoding_mismatch",
                             sourceEnc, jspConfigPageEnc);
            }
        }
    }

    /*
     * Checks to see if the given URI is matched by a URL pattern specified in
     * a jsp-property-group in web.xml, and if so, returns the value of the
     * <page-encoding> element.
     *
     * @param absFileName The URI to match
     *
     * @return The value of the <page-encoding> attribute of the 
     * jsp-property-group with matching URL pattern
     */
    private String getJspConfigPageEncoding(String absFileName)
            throws JasperException {

	JspConfig jspConfig = ctxt.getOptions().getJspConfig();
	JspProperty jspProperty = jspConfig.findJspProperty(absFileName);
	return jspProperty.getPageEncoding();
    }

    /**
     * Determines the syntax (standard or XML) and page encoding properties
     * for the given file, and stores them in the 'isXml' and 'sourceEnc'
     * instance variables, respectively.
     */
    private void determineSyntaxAndEncoding(String absFileName,
					    JarFile jarFile,
					    String jspConfigPageEnc)
	        throws JasperException, IOException {

	isXml = false;

	/*
	 * 'true' if the syntax (XML or standard) of the file is given
	 * from external information: either via a JSP configuration element,
	 * the ".jspx" suffix, or the enclosing file (for included resources)
	 */
	boolean isExternal = false;

	/*
	 * Indicates whether we need to revert from temporary usage of
	 * "ISO-8859-1" back to "UTF-8"
	 */
	boolean revert = false;

        JspConfig jspConfig = ctxt.getOptions().getJspConfig();
        JspProperty jspProperty = jspConfig.findJspProperty(absFileName);
        if (jspProperty.isXml() != null) {
            // If <is-xml> is specified in a <jsp-property-group>, it is used.
            isXml = JspUtil.booleanValue(jspProperty.isXml());
	    isExternal = true;
	} else if (absFileName.endsWith(".jspx")
		   || absFileName.endsWith(".tagx")) {
	    isXml = true;
	    isExternal = true;
	}
	
	if (isExternal && !isXml) {
	    // JSP (standard) syntax. Use encoding specified in jsp-config
	    // if provided.
	    sourceEnc = jspConfigPageEnc;
	    if (sourceEnc != null) {
		return;
	    }
	    // We don't know the encoding
	    sourceEnc = "ISO-8859-1";
	} else {
	    // XML syntax or unknown, (auto)detect encoding ...
	    Object[] ret = XMLEncodingDetector.getEncoding(absFileName,
							   jarFile, ctxt, err);
	    sourceEnc = (String) ret[0];
	    if (((Boolean) ret[1]).booleanValue()) {
		isEncodingSpecifiedInProlog = true;
	    }
	    if (ret[2] != null && ((Boolean) ret[2]).booleanValue()) {
                hasBom = true;
	    }

	    if (!isXml && sourceEnc.equalsIgnoreCase("utf-8") && !hasBom) {
		/*
		 * We don't know if we're dealing with XML or standard syntax.
		 * Therefore, we need to check to see if the page contains
		 * a <jsp:root> element.
		 *
                 * We need to be careful, because the page may be encoded in
                 * ISO-8859-1 (or something entirely different: UTF-8 was 
                 * chosen as the default, for lack of better alternative),
                 * and may contain byte sequences that will cause a UTF-8
                 * converter to throw exceptions. 
		 *
		 * It is safe to use a source encoding of ISO-8859-1 in this
		 * case, as there are no invalid byte sequences in ISO-8859-1,
		 * and the byte/character sequences we're looking for (i.e.,
		 * <jsp:root>) are identical in either encoding (both UTF-8
		 * and ISO-8859-1 are extensions of ASCII).
		 */
		sourceEnc = "ISO-8859-1";
		revert = true;
	    }
	}

	if (isXml) {
	    // (This implies 'isExternal' is TRUE.)
	    // We know we're dealing with a JSP document (via JSP config or
	    // ".jspx" suffix), so we're done.
	    return;
	}

	/*
	 * At this point, 'isExternal' or 'isXml' is FALSE.
	 * Search for jsp:root action, in order to determine if we're dealing 
	 * with XML or standard syntax (unless we already know what we're 
	 * dealing with, i.e., when 'isExternal' is TRUE and 'isXml' is FALSE).
	 * No check for XML prolog, since nothing prevents a page from
	 * outputting XML and still using JSP syntax (in this case, the 
	 * XML prolog is treated as template text).
	 */
	JspReader jspReader = null;
	try {
	    jspReader = new JspReader(ctxt, absFileName, sourceEnc, jarFile,
				      err);
	} catch (FileNotFoundException ex) {
	    throw new JasperException(ex);
	}
        jspReader.setSingleFile(true);
        Mark startMark = jspReader.mark();
	if (!isExternal) {
	    jspReader.reset(startMark);
	    if (hasJspRoot(jspReader)) {
	        isXml = true;
		if (revert) sourceEnc = "UTF-8";
		return;
	    } else {
	        isXml = false;
	    }
	}

	/*
	 * At this point, we know we're dealing with JSP syntax.
	 * If an XML prolog is provided, it's treated as template text.
	 * Determine the page encoding from the page directive, unless it's
	 * specified via JSP config.
	 */
        if (!hasBom) {
            sourceEnc = jspConfigPageEnc;
        }
	if (sourceEnc == null) {
	    sourceEnc = getPageEncodingForJspSyntax(jspReader, startMark);
	    if (sourceEnc == null) {
		// Default to "ISO-8859-1" per JSP spec
		sourceEnc = "ISO-8859-1";
		isDefaultPageEncoding = true;
	    }
	}
    }
    
    /*
     * Determines page source encoding for page or tag file in JSP syntax,
     * by reading (in this order) the value of the 'pageEncoding' page
     * directive attribute, or the charset value of the 'contentType' page
     * directive attribute.
     *
     * @return The page encoding, or null if not found
     */
    private String getPageEncodingForJspSyntax(JspReader jspReader,
					       Mark startMark)
	        throws JasperException {

	String encoding = null;
        String saveEncoding = null;

        jspReader.reset(startMark);

	/*
	 * Determine page encoding from directive of the form <%@ page %>,
	 * <%@ tag %>, <jsp:directive.page > or <jsp:directive.tag >.
	 */
        while (true) {
            if (jspReader.skipUntil("<") == null) {
                break;
            }
            // If this is a comment, skip until its end
            if (jspReader.matches("%--")) {
                if (jspReader.skipUntil("--%>") == null) {
                    // error will be caught in Parser
                    break;
                }
                continue;
            }
            boolean isDirective = jspReader.matches("%@");
            if (isDirective) {
	        jspReader.skipSpaces();
            }
            else {
                isDirective = jspReader.matches("jsp:directive.");
            }
            if (!isDirective) {
                continue;
            }

	    // compare for "tag ", so we don't match "taglib"
	    if (jspReader.matches("tag ") || jspReader.matches("page")) {

		jspReader.skipSpaces();
                Attributes attrs = Parser.parseAttributes(this, jspReader);
		encoding = getPageEncodingFromDirective(attrs, "pageEncoding");
                if (encoding != null) {
                    break;
                }
		encoding = getPageEncodingFromDirective(attrs, "contentType");
                if (encoding != null) {
                    saveEncoding = encoding;
                }
	    }
	}

        if (encoding == null) {
            encoding = saveEncoding;
        }

	return encoding;
    }

    /*
     * Scans the given attributes for the attribute with the given name,
     * which is either 'pageEncoding' or 'contentType', and returns the
     * specified page encoding.
     *
     * In the case of 'contentType', the page encoding is taken from the
     * content type's 'charset' component.
     *
     * @param attrs The page directive attributes
     * @param attrName The name of the attribute to search for (either
     * 'pageEncoding' or 'contentType')
     *
     * @return The page encoding, or null
     */
    private String getPageEncodingFromDirective(Attributes attrs,
                                                String attrName) {
	String value = attrs.getValue(attrName);
        if (attrName.equals("pageEncoding")) {
            return value;
        }

        // attrName = contentType
        String contentType = value;
        String encoding = null;
        if (contentType != null) {
	    int loc = contentType.indexOf(CHARSET);
	    if (loc != -1) {
		encoding = contentType.substring(loc + CHARSET.length());
	    }
	}

	return encoding;
    }

    /*
     * Resolve the name of the file and update baseDirStack() to keep track of
     * the current base directory for each included file.
     * The 'root' file is always an 'absolute' path, so no need to put an
     * initial value in the baseDirStack.
     */
    private String resolveFileName(String inFileName) {
        String fileName = inFileName.replace('\\', '/');
        boolean isAbsolute = fileName.startsWith("/");
	fileName = isAbsolute ? fileName : baseDirStack.peek() + fileName;
        String baseDir = fileName.substring(0, fileName.lastIndexOf("/") + 1);
	baseDirStack.push(baseDir);
	return fileName;
    }

    /*
     * Checks to see if the given page contains, as its first element, a <root>
     * element whose prefix is bound to the JSP namespace, as in:
     *
     * <wombat:root xmlns:wombat="http://java.sun.com/JSP/Page" version="1.2">
     *   ...
     * </wombat:root>
     *
     * @param reader The reader for this page
     *
     * @return true if this page contains a root element whose prefix is bound
     * to the JSP namespace, and false otherwise
     */
    private boolean hasJspRoot(JspReader reader) throws JasperException {

	// <prefix>:root must be the first element
	Mark start = null;
	while ((start = reader.skipUntil("<")) != null) {
	    int c = reader.nextChar();
	    if (c != '!' && c != '?') break;
	}
	if (start == null) {
	    return false;
	}
	Mark stop = reader.skipUntil(":root");
	if (stop == null) {
	    return false;
	}
	// call substring to get rid of leading '<'
	String prefix = reader.getText(start, stop).substring(1);

	start = stop;
	stop = reader.skipUntil(">");
	if (stop == null) {
	    return false;
	}

	// Determine namespace associated with <root> element's prefix
	String root = reader.getText(start, stop);
	String xmlnsDecl = "xmlns:" + prefix;
	int index = root.indexOf(xmlnsDecl);
	if (index == -1) {
	    return false;
	}
	index += xmlnsDecl.length();
	while (index < root.length()
	           && Character.isWhitespace(root.charAt(index))) {
	    index++;
	}
	if (index < root.length() && root.charAt(index) == '=') {
	    index++;
	    while (index < root.length()
		       && Character.isWhitespace(root.charAt(index))) {
		index++;
	    }
	    if (index < root.length() && root.charAt(index++) == '"'
		    && root.regionMatches(index, JSP_URI, 0,
					  JSP_URI.length())) {
		return true;
	    }
	}

	return false;
    }

    private JarFile getJarFile(URL jarFileUrl) throws IOException {
	JarFile jarFile = null;

	if (jarFileUrl != null) {
	    JarURLConnection conn = (JarURLConnection) jarFileUrl.openConnection();
	    conn.setUseCaches(false);
	    conn.connect();
	    jarFile = conn.getJarFile();
	}

	return jarFile;
    }

}