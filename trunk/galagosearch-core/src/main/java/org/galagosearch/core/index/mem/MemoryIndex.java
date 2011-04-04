// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index.mem;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.galagosearch.core.retrieval.structured.ExtentIndexIterator;
import org.galagosearch.core.parse.NumberedDocument;
import org.galagosearch.core.parse.Porter2Stemmer;
import org.galagosearch.core.parse.Tag;
import org.galagosearch.core.retrieval.structured.IndexIterator;
import org.galagosearch.core.retrieval.structured.NumberedDocumentDataIterator;
import org.galagosearch.tupleflow.IncompatibleProcessorException;
import org.galagosearch.tupleflow.InputClass;
import org.galagosearch.tupleflow.NullProcessor;
import org.galagosearch.tupleflow.Parameters;
import org.galagosearch.tupleflow.Processor;
import org.galagosearch.tupleflow.TupleFlowParameters;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.execution.Verified;

/*
 * Memory Index
 * 
 * Assumptions; documents are added sequentially
 *
 * author: sjh, schui
 * 
 */
@Verified
@InputClass(className = "org.galagosearch.core.parse.NumberedDocument")
public class MemoryIndex implements Processor<NumberedDocument> {

  private boolean stemming;
  private int lastDocId;
  private MemoryManifest manifest;
  private MemoryDocumentLengths documentLengths;
  private MemoryDocumentNames documentNames;
  private MemoryExtents extents;
  private MemoryPostings postings;
  private MemoryPostings stemmedPostings;

  public MemoryIndex(TupleFlowParameters parameters) {
    this(parameters.getXML());
  }

  public MemoryIndex(Parameters parameters) {
    stemming = (boolean) parameters.get("stemming", false);
    int documentNumberOffset = (int) parameters.get("firstDocumentId", 0);
    lastDocId = documentNumberOffset - 1;

    manifest = new MemoryManifest();
    manifest.setOffset(documentNumberOffset);
    documentLengths = new MemoryDocumentLengths(documentNumberOffset);
    documentNames = new MemoryDocumentNames(documentNumberOffset);
    extents = new MemoryExtents();
    postings = new MemoryPostings();
    
    if (stemming) {
      stemmedPostings = new MemoryPostings();
    }

  }

  public void process(NumberedDocument doc) throws IOException {
    assert (doc.number == lastDocId + 1) : "Recieved document number " + doc.number + " expected " + (lastDocId + 1);
    lastDocId = doc.number;

    try {
      manifest.addDocument(doc.terms.size());
      documentLengths.addDocument(doc.number, doc.terms.size());
      documentNames.addDocument(doc.number, doc.identifier);
      for (Tag tag : doc.tags) {
        // I assume that tags are in order (+begin)
        extents.addDocumentExtent(tag.name, doc.number, tag);
      }
      for (int i = 0; i < doc.terms.size(); i++) {
        if (doc.terms.get(i) != null) {
          postings.addPosting(doc.terms.get(i), doc.number, i);
        }
      }

      if (stemming) {
        Porter2Stemmer stemmer = new Porter2Stemmer();
        stemmer.setProcessor(new NullProcessor());
        stemmer.process(doc);
        for (int i = 0; i < doc.terms.size(); i++) {
          if (doc.terms.get(i) != null) {
            stemmedPostings.addPosting(doc.terms.get(i), doc.number, i);
          }
        }

      }

    } catch (IndexOutOfBoundsException e) {
      //logger.log(Level.INFO, "Problem indexing document: " + e.toString());
      throw new RuntimeException("Memory Indexer failed to add document: " + doc.identifier);
    } catch (IncompatibleProcessorException e) {
      //logger.log(Level.INFO, "Problem stemming document: " + e.toString());
    }
  }

  public void close() throws IOException {
    /*
     * flush to disk
     * 
     * File temp = Utility.createGalagoTempDir();
     * System.err.println(temp.getAbsoluteFile());
     * (new FlushToDisk()).flushMemoryIndex(this, temp.getAbsolutePath());
     *
     */

    // try to free some memory up
    manifest = null;
    documentLengths = null;
    documentNames = null;
    extents = null;
    postings = null;
    stemmedPostings = null;
  }

  /*
   * Index functions
   * 
   * Needed to support index merging / index dumping
   */
  public long getCollectionLength() {
    return manifest.getCollectionLength();
  }

  public long getDocumentCount() {
    return manifest.getDocumentCount();
  }

  public int getDocumentNumberOffset() {
    return manifest.getOffset();
  }

  public Parameters getManifest() {
    return manifest.makeParameters();
  }

  public String getDocumentName(int document) throws IOException {
    return documentNames.getDocumentName(document);
  }

  public NumberedDocumentDataIterator getDocumentNamesIterator() {
    return documentNames.getIterator();
  }

  public NumberedDocumentDataIterator getDocumentLengthsIterator() {
    return documentLengths.getIterator();
  }

  public IndexIterator getPartIterator(String part) throws IOException {
    return getExtentIterator(part);
  }

  public ExtentIndexIterator getExtentIterator(String part) throws IOException {
    if (part.equals("extents")) {
      return extents.getIterator();
    }
    if (part.equals("postings")) {
      return postings.getIterator();
    }
    if (part.equals("stemmedPostings") && stemming) {
      return postings.getIterator();
    }

    return null;
  }

  public boolean isStemmed() {
    return stemming;
  }
}
