// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index;

import java.io.DataInput;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.core.retrieval.structured.*;
import org.galagosearch.core.util.ExtentArray;
import org.galagosearch.tupleflow.BufferedFileDataStream;
import org.galagosearch.tupleflow.DataStream;
import org.galagosearch.tupleflow.Parameters;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.VByteInput;

/**
 *
 * @author trevor
 */
public class ExtentIndexReader extends KeyListReader {

  public class KeyIterator extends KeyListReader.Iterator {

    public KeyIterator(GenericIndexReader reader) throws IOException {
      super(reader);
    }

    @Override
    public String getValueString() {
      ListIterator it;
      long count = -1;
      try {
        it = new ListIterator(iterator);
        count = it.totalEntries();
      } catch (IOException ioe) {
      }

      StringBuilder sb = new StringBuilder();
      sb.append(Utility.toString(iterator.getKey())).append(", List Value: size=");
      if (count > 0) {
        sb.append(count);
      } else {
        sb.append("Unknown");
      }
      return sb.toString();
    }

    public ValueIterator getValueIterator() throws IOException {
      return new ListIterator(iterator);
    }
  }

  public class ListIterator extends KeyListReader.ListIterator implements CountValueIterator, ExtentValueIterator {

    GenericIndexReader.Iterator iterator;
    DataStream dataStream;
    VByteInput data;
    long startPosition, endPosition;
    int documentCount;
    int options;
    int currentDocument;
    ExtentArray extents;
    int documentIndex;
    // skip support
    VByteInput skips;
    int skipDistance;
    int skipsRead;
    int numSkips;
    int nextSkipDocument;
    long lastSkipPosition;

    public ListIterator(GenericIndexReader.Iterator iterator) throws IOException {
      extents = new ExtentArray();
      reset(iterator);
    }

    public void reset(GenericIndexReader.Iterator i) throws IOException {
      iterator = i;
      startPosition = iterator.getValueStart();
      endPosition = iterator.getValueEnd();
      dataLength = iterator.getValueLength();
      key = iterator.getKey();
      reset();
    }

    public void reset() throws IOException {
      currentDocument = 0;
      extents.reset();
      documentCount = 0;
      documentIndex = 0;
      initialize();
    }

    public String getEntry() {
      StringBuilder builder = new StringBuilder();
      builder.append(getKey());
      builder.append(",");
      builder.append(currentDocument);
      for (int i = 0; i < extents.getPositionCount(); ++i) {
        builder.append(",(");
        builder.append(extents.getBuffer()[i].begin);
        builder.append(",");
        builder.append(extents.getBuffer()[i].end);
        builder.append(")");
      }
      return builder.toString();
    }

    private void initialize() throws IOException {
//      DataStream valueStream = iterator.getSubValueStream(0, dataLength);
      DataStream valueStream = iterator.getValueStream();
      DataInput stream = new VByteInput(valueStream);

      options = stream.readInt();
      documentCount = stream.readInt();
      currentDocument = 0;
      documentIndex = 0;

      long dataStart = 0;
      long dataLength = 0;

      // check for skips
      if ((options & HAS_SKIPS) == HAS_SKIPS) {
        skipDistance = stream.readInt();
        numSkips = stream.readInt();
        dataLength = stream.readLong();
        dataStart = valueStream.getPosition();
      } else {
        skipDistance = 0;
        numSkips = 0;
        dataStart = valueStream.getPosition();
        dataLength = endPosition - dataStart;
      }

      // Load data stream
      dataStream = iterator.getSubValueStream(dataStart, dataLength);
      data = new VByteInput(dataStream);

      // Now load skips if they're in
      if (skipDistance > 0) {
        long skipStart = dataStart + dataLength;
        long skipLength = endPosition - skipStart;
        skips = new VByteInput( iterator.getSubValueStream(skipStart, skipLength) );
        nextSkipDocument = skips.readInt();
        lastSkipPosition = 0;
        skipsRead = 0;
      } else {
        skips = null;
      }

      loadExtents();
    }

    public long totalEntries() {
      return documentCount;
    }

    public boolean next() throws IOException {
      extents.reset();
      documentIndex = Math.min(documentIndex + 1, documentCount);

      if (!isDone()) {
        loadExtents();
        return true;
      }
      return false;
    }

    @Override
    public boolean hasMatch(int document) {
      return (!isDone() && currentCandidate() == document);
    }

    // If we have skips - it's go time
    @Override
    public boolean moveTo(int document) throws IOException {
      if (skips != null && document > nextSkipDocument) {
        // if we're here, we're skipping
        while (skipsRead < numSkips
                && document > nextSkipDocument) {
          skipOnce();
        }

        // Reposition the data stream
        dataStream.seek(lastSkipPosition);
        documentIndex = (int) (skipsRead * skipDistance) - 1;
      }

      // linear from here
      while (document > currentDocument && next());
      return hasMatch(document);
    }

    private void skipOnce() throws IOException {
      assert skipsRead < numSkips;

      // move forward once in the skip stream
      long currentSkipPosition = lastSkipPosition + skips.readInt();
      currentDocument = (int) nextSkipDocument;

      // May be at the end of the buffer
      if (skipsRead + 1 == numSkips) {
        nextSkipDocument = Integer.MAX_VALUE;
      } else {
        nextSkipDocument += skips.readInt();
      }
      skipsRead++;
      lastSkipPosition = currentSkipPosition;
    }

    private void loadExtents() throws IOException {
      currentDocument += data.readInt();
      int extentCount = data.readInt();
      int begin = 0;

      for (int i = 0; i < extentCount; i++) {
        int deltaBegin = data.readInt();
        int extentLength = data.readInt();
        long value = data.readLong();

        begin = deltaBegin + begin;
        int end = begin + extentLength;

        extents.add(currentDocument, begin, end, value);
      }
    }

    public int currentCandidate() {
      return currentDocument;
    }

    public int count() {
      return extents.getPositionCount();
    }

    public ExtentArray getData() {
      return extents;
    }

    public ExtentArray extents() {
      return extents;
    }

    public boolean isDone() {
      return (documentIndex >= documentCount);
    }
  }

  public ExtentIndexReader(GenericIndexReader reader) throws IOException {
    super(reader);
  }

  public KeyIterator getIterator() throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator();
    if(iterator != null){
      return new KeyIterator(reader);
    } else {
      return null;
    }
  }

  public ListIterator getListIterator() throws IOException {
    return new ListIterator(reader.getIterator());
  }

  public ListIterator getExtents(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));

    if (iterator != null) {
      return new ListIterator(iterator);
    }
    return null;
  }

  public CountIterator getCounts(String term) throws IOException {
    GenericIndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));

    if (iterator != null) {
      return new ListIterator(iterator);
    }
    return null;
  }

  public void close() throws IOException {
    reader.close();
  }

  public HashMap<String, NodeType> getNodeTypes() {
    HashMap<String, NodeType> nodeTypes = new HashMap<String, NodeType>();
    nodeTypes.put("extents", new NodeType(Iterator.class));
    return nodeTypes;
  }

  public ValueIterator getIterator(Node node) throws IOException {
    if (node.getOperator().equals("extents")) {
      return getExtents(node.getDefaultParameter());
    } else {
      throw new UnsupportedOperationException("Node type " + node.getOperator()
              + " isn't supported.");
    }
  }
}
