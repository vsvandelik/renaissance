package org.renaissance.xml;

import org.renaissance.Benchmark;
import org.renaissance.BenchmarkContext;
import org.renaissance.BenchmarkResult;
import org.renaissance.BenchmarkResult.Validators;
import org.renaissance.License;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

import static org.renaissance.Benchmark.*;

@Name("xml-ops")
@Group("xml")
@Summary("A very easy XML workload testing generating, querying and modifying XML docs.")
@Licenses(License.MIT)
public final class XMLOps implements Benchmark {
    @Override
    public BenchmarkResult run(BenchmarkContext c)  {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element rootElement = doc.createElement("root");
            buildTree(doc, rootElement);
            doc.appendChild(rootElement);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);

            StringWriter sw = new StringWriter();
            StreamResult stringResult = new StreamResult(sw);

            transformer.transform(source, stringResult);
            String xmlDocumentInString = sw.toString();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Result outputTarget = new StreamResult(outputStream);
            transformer.transform(source, outputTarget);
            InputStream xmlDocumentInStream = new ByteArrayInputStream(outputStream.toByteArray());

            parseTree(doc, rootElement, builder, xmlDocumentInString, xmlDocumentInStream);
            queryTree(doc, rootElement);
            modifyTree(doc, rootElement);

            return Validators.simple("xml root failing attributes", 0, rootElement.getAttributes().getLength());

        } catch (Exception exception){
            throw new AssertionError("Some error occurs");
        }


    }

    static void parseTree(Document doc, Element rootElement, DocumentBuilder builder, String xmlDocumentInString, InputStream xmlDocumentInStream) throws IOException, SAXException {
        Document docFromString = builder.parse(new InputSource(new StringReader(xmlDocumentInString)));
        Document docFromStream = builder.parse(xmlDocumentInStream);

        if(!docFromString.isEqualNode(docFromStream))
            rootElement.setAttribute("parsetree", "false");
    }

    static void queryTree(Document doc, Element rootElement) {
        queryByTagName(doc, rootElement);
        queryAllNodesByTextContent(doc, rootElement);
        querySelectedNodesByTagByTextContent(doc, rootElement);
    }

    static void modifyTree(Document doc, Element rootElement){
        Element root2 = doc.createElement("root2");
        Element result1 = doc.createElement("result-1");
        root2.appendChild(result1);
        for (int i = 0; i < doc.getChildNodes().getLength(); i++) {
            Node rootChild = doc.getChildNodes().item(i);
            result1.appendChild(rootChild);
        }

        if(result1.getChildNodes().getLength() == rootElement.getChildNodes().getLength())
            rootElement.setAttribute("copyingchilds", "false");

        Element result2 = doc.createElement("result-2");
        root2.appendChild(result2);
        NodeList subChild = doc.getElementsByTagName("subchild");
        for (int i = 0; i < subChild.getLength(); i++) {
            Node subChildNode = subChild.item(i);

            Element node = doc.createElement("subchild");
            node.setAttribute("attr", subChildNode.getTextContent());
            node.appendChild(doc.createTextNode("found"));

            result2.appendChild(node);
        }

        root2.appendChild(rootElement);

        if(root2.getChildNodes().getLength() != 3)
            rootElement.setAttribute("newroot", "false");
    }

    static void buildTree(Document doc, Element root) {
        createBroadSubtrees(doc, root);
        createDeepSubtree(doc, root);
    }

    static void createBroadSubtrees(Document doc, Element root){
        for (int i = 0; i < 50; i++){
            Element rootChild = doc.createElement(String.format("child-%d", i));

            for(int j = 0; j < 50; j++){
                Element child = doc.createElement("subchild");
                child.appendChild(doc.createTextNode(String.format("LEAF-%d-%d", i, j)));
                rootChild.appendChild(child);
            }

            root.appendChild(rootChild);
        }
    }

    static void createDeepSubtree(Document doc, Element root){
        Element deepRootChild = doc.createElement("deepchildren");

        Element deep = deepRootChild;
        for (int i = 0; i < 50; i++){
            Element deepChild = doc.createElement("deepchild");
            deep.appendChild(deepChild);
            deep = deepChild;
        }

        Element deepLeaf = doc.createElement("deepleaf");
        deepLeaf.appendChild(doc.createTextNode("LEAF"));
        deep.appendChild(deepLeaf);

        root.appendChild(deepRootChild);
    }

    static void queryByTagName(Document doc, Element rootElement){
        NodeList deepLeafs = doc.getElementsByTagName("deepleaf");
        if(deepLeafs.getLength() != 1)
            rootElement.setAttribute("querybytagname", "false");
    }

    static void queryAllNodesByTextContent(Document doc, Element rootElement){
        int foundCount = 0;
        for (int i = 0; i < doc.getChildNodes().getLength(); i++) {
            Node rootChild =  doc.getChildNodes().item(i);
            if (rootChild.getNodeType() != Node.ELEMENT_NODE) continue;

            for(int j = 0; j < rootChild.getChildNodes().getLength(); j++) {
                Node subChild =  rootChild.getChildNodes().item(j);
                if (subChild.getNodeType() != Node.ELEMENT_NODE) continue;

                for(int k = 0; k < subChild.getChildNodes().getLength(); k++) {
                    Node subSubChild =  subChild.getChildNodes().item(k);
                    if (subSubChild.getNodeType() != Node.ELEMENT_NODE) continue;

                    if(subSubChild.getTextContent().equals("LEAF-99-100"))
                        foundCount++;

                }

            }
        }

        if(foundCount != 0)
            rootElement.setAttribute("allnodescheck", "false");
    }

    static void querySelectedNodesByTagByTextContent(Document doc, Element rootElement){
        int foundCount = 0;
        NodeList selectedNodes = doc.getElementsByTagName("deepleaf");
        for (int i = 0; i < selectedNodes.getLength(); i++) {
            Node node = selectedNodes.item(i);

            if(node.getTextContent().equals("LEAF"))
                foundCount++;
        }

        if(foundCount != 1)
            rootElement.setAttribute("selectedNodesCheck", "false");
    }
}
