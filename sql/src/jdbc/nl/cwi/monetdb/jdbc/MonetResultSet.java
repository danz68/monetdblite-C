package nl.cwi.monetdb.jdbc;

import java.sql.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.math.*;
import java.net.*;

/**
 * A ResultSet suitable for the Monet database
 * <br /><br />
 * A table of data representing a database result set, which is usually
 * generated by executing a statement that queries the database.
 * <br /><br />
 * A ResultSet object maintains a cursor pointing to its current row of data.
 * Initially the cursor is positioned before the first row. The next method
 * moves the cursor to the next row, and because it returns false when there
 * are no more rows in the ResultSet object, it can be used in a while loop to
 * iterate through the result set.
 * <br /><br />
 * The current state of this ResultSet is that it supports positioning in the
 * result set, absolute and relative. Due to the way the Mapi protocol works
 * there is no performance difference between FORWARD_ONLY or scrollable in
 * both directions.
 *
 * @author Fabian Groffen <Fabian.Groffen@cwi.nl>
 * @version 0.3 (beta release)
 */
public class MonetResultSet implements ResultSet {
	private String[] line;
	private String[] result;
	private int lastColumnRead = -1;
	private boolean closed = false;
	private int curRow = 0;

	// a blank final is immutable once assigned in the constructor
	private final MonetStatement.CacheThread cache;
	private final String[] columns;
	private final String[] types;
	private final String tableID;
	private final int tupleCount;

	private final MonetSocket monet;
	private final Statement statement;

	private int type = TYPE_FORWARD_ONLY;
	private int concurrency = CONCUR_READ_ONLY;

	/**
	 * Main constructor, sends query to Monet and reads header
	 *
	 * @param monet a valid Monet object to communicate to
	 * @param query a query String to execute
	 * @throws IllegalArgumentException is monet or query is null or empty
	 * @throws IOException if communicating with monet failed
	 * @throws SQLException is a protocol error occurs
	 */
	MonetResultSet(
		MonetSocket monet,
		Statement statement,
		String query,
		int resultSetType,
		int resultSetConcurrency)
		throws IllegalArgumentException, IOException, SQLException
	{
		if (monet == null ||
			statement == null ||
			!(query != null && !(query = query.trim()).equals(""))
		)
			throw new IllegalArgumentException("Monet or query is null or empty!");

		this.monet = monet;
		this.statement = statement;
		this.type = resultSetType;
		this.concurrency = resultSetConcurrency;
		this.cache = ((MonetStatement)statement).cache;
		// well there is only one supported concurrency, so we don't have to
		// bother about that

		// set the reply size for this query. If it is set to 0 we get a
		// prompt after the server sent it's header
		int maxRows = statement.getMaxRows();
		int cacheSize = statement.getFetchSize();
		((MonetConnection)statement.getConnection()).setReplySize(
			maxRows != 0 ? Math.min(maxRows, cacheSize) : cacheSize);

		// check the query (make sure it ends with ';' and escape newlines)
		query = query.replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r");
		if (!query.endsWith(";")) query += ";";

		// let the cache thread do it's work
		// use lowercase 's' in order to tell the server we don't want a
		// continuation prompt if it needs more to complete the query
		cache.newResult("s" + query);
		Thread.yield();

		// read header info (wait for it if it's not there)
		Map headers = ((MonetStatement)statement).headers;
		synchronized(headers) {
			while (headers.size() == 0) {
				try {
					headers.wait();
				} catch(InterruptedException e) {
					// shit!
					throw new SQLException("Interruption while waiting for headers: " + e.getMessage());
				}
			}
		}

		// check if there was an error
		synchronized(cache) {
			if (cache.hasError())
				throw new SQLException(cache.getError());
		}

		if (headers.get("emptyheader") != null) {
			columns = new String[0];
			types = null;
			tableID = null;
			tupleCount = -1;
		} else {
			columns = (String[])headers.get("name");
			types = (String[])headers.get("type");
			tableID = ((MonetStatement)statement).resultID;
			tupleCount = ((MonetStatement)statement).tupleCount;
		}

		// create result array
		result = new String[columns.length];
	}

	//== methods of interface ResultSet

	/**
	// Chapter 14.2.2 Sun JDBC 3.0 Specification
	 * Moves the cursor to the given row number in this ResultSet object.
	 * <br /><br />
	 * If the row number is positive, the cursor moves to the given row number
	 * with respect to the beginning of the result set. The first row is row 1,
	 * the second is row 2, and so on.
	 * <br /><br />
	 * If the given row number is negative, the cursor moves to an absolute row
	 * position with respect to the end of the result set. For example, calling
	 * the method absolute(-1) positions the cursor on the last row; calling the
	 * method absolute(-2) moves the cursor to the next-to-last row, and so on.
	 * <br /><br />
	 * An attempt to position the cursor beyond the first/last row in the result
	 * set leaves the cursor before the first row or after the last row.
	 * Note: calling absolute(1) is the same as calling first(). Calling
	 *       absolute(-1) is the same as calling last().
	 *
	 * @param row the number of the row to which the cursor should move. A
	 *        positive number indicates the row number counting from the
	 *        beginning of the result set; a negative number indicates the row
	 *        number counting from the end of the result set
	 * @return true if the cursor is on the result set; false otherwise
	 * @throws SQLException if a database access error occurs, or the result set
	 *         type is TYPE_FORWARD_ONLY
	 */
 	public boolean absolute(int row) throws SQLException {
		if (row != curRow + 1 && type == TYPE_FORWARD_ONLY) throw
			new SQLException("(Absolute) positioning not allowed on forward " +
				" only result sets!");

		if (closed) throw new SQLException("ResultSet is closed!");

		// first calculate what the JDBC row is
		if (row < 0) {
			// calculate the negatives...
			row = tupleCount + row + 1;
		}
		// now place the row not farther than just before or after the result
		if (row < 0) row = 0;	// before first
		else if (row > tupleCount + 1) row = tupleCount + 1;	// after last

		String tmpLine = cache.getLine(row - 1);

		// store it
		curRow = row;

		if (tmpLine == null) return(false);

		// remove brackets from result
		tmpLine = tmpLine.substring(1, tmpLine.length() - 2);

		// extract separate fields by examining string, char for char
		boolean inString = false, escaped = false;
		int cursor = 0, column = 0, i = 0;
		for (; i < tmpLine.length(); i++) {
			switch(tmpLine.charAt(i)) {
				case '\'':
					escaped = !escaped;
				break;
				default:
					escaped = false;
				break;
				case '"':
					/**
					 * If all strings are wrapped between two quotes, a \" can
					 * never exist outside a string. Thus if we believe that we
					 * are not within a string, we can safely assume we're about
					 * to enter a string if we found a quote.
					 * If we are in a string we should stop being in a string if
					 * we find a quote which is not prefixed by a \, for that
					 * would be an escaped quote. However, a nasty situation can
					 * occur where the string is like "test \\" as obvious, a
					 * test for a \ in front of a " doesn't hold here cases.
					 */
					if (!inString) {
						inString = true;
					} else if (!escaped) {
						inString = false;
					}

					// reset escaped flag
					escaped = false;
				break;
				case '\t':
					if (!inString &&
						(i > 0 && tmpLine.charAt(i - 1) == ','))
					{
						// split!
						result[column++] =
							tmpLine.substring(cursor, i - 1).trim();
						cursor = i + 1;
					}

					// reset escaped flag
					escaped = false;
				break;
			}
		}
		// put the left over (if any) in the next column (should be there!!!)
		if (i - cursor > 0)
			result[column++] = tmpLine.substring(cursor).trim();
		// check if this result is of the size we expected it to be
		if (column != columns.length) throw new AssertionError("Illegal result length: " + column + "\nlast read: " + result[column - 1]);
		// trim spaces off all columns and unquote + unescape if they are quoted
		for (i = 0; i < result.length; i++) {
			if (result[i].equals("nil")) {
				result[i] = null;
			} else 	if (result[i].startsWith("\"") && result[i].endsWith("\"")) {
				result[i] = result[i].substring(1, result[i].length() - 1);
				// now unescape (see Monet source src/gdk/gdk_atoms.mx (strFromStr))
				result[i] = result[i].replaceAll("\\\\\\\\", "\\\\");	// \
				result[i] = result[i].replaceAll("\\\\\"", "\\\"");		// "
				result[i] = result[i].replaceAll("\\\\\'", "\\\'");		// '
				result[i] = result[i].replaceAll("\\\\r", "\\r");		// \r
				result[i] = result[i].replaceAll("\\\\n", "\\n");		// \n
				result[i] = result[i].replaceAll("\\\\t", "\\t");		// \t
			// the thing below will not work
			//	result[i] = result[i].replaceAll("\\\\([0-3][0-7]{2})", "\\0\\1");	// \377 octal
			}
		}

		// reset lastColumnRead
		lastColumnRead = -1;

		return(true);
	}

	/**
	 * Moves the cursor to the end of this ResultSet object, just after the last
	 * row. This method has no effect if the result set contains no rows.
	 *
	 * @throws SQLException if a database access error occurs or the result set
	 *         type is TYPE_FORWARD_ONLY
	 */
	public void afterLast() throws SQLException {
		absolute(tupleCount + 1);
	}

	/**
	 * Moves the cursor to the front of this ResultSet object, just before the
	 * first row. This method has no effect if the result set contains no rows.
	 *
	 * @throws SQLException if a database access error occurs or the result set
	 *         type is TYPE_FORWARD_ONLY
	 */
	public void beforeFirst() throws SQLException {
		absolute(0);
	}

	public void 	cancelRowUpdates() {}
	public void 	clearWarnings() {}

	/**
	 * Releases this ResultSet object's database (and JDBC) resources
	 * immediately instead of waiting for this to happen when it is
	 * automatically closed.
	 *
	 * @throws SQLException if a database access error occurs
	 */
	public void close() {
		if (!closed) {
			closed = true;
			// make sure we own the lock on monet
			synchronized (monet) {
				try {
					// commit the transaction if applicable
					if (statement.getConnection().getAutoCommit())
					  ((MonetConnection)statement.getConnection()).sendCommit();

					// send command to server indicating we're done with this
					// result only if we had an ID in the header... Currently
					// on updates, inserts and deletes there is no header at all
					if (tableID != null) {
						monet.writeln("Xclose " + tableID);
						// read till prompt (we need to wait for the server to
						// be ready)
						monet.waitForPrompt();
					}
				} catch (IOException e) {
					// too bad, we're probably closed already
				} catch (SQLException e) {
					// too bad again
				}
			}
		}
	}

	public void 	deleteRow() {}

	/**
	// Chapter 14.2.3 from Sun JDBC 3.0 specification
	 * Maps the given ResultSet column name to its ResultSet column index.
	 * Column names supplied to getter methods are case insensitive. If a select
	 * list contains the same column more than once, the first instance of the
	 * column will be returned.
	 *
	 * @param columnName the name of the column
	 * @return the column index of the given column name
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public int findColumn(String columnName) throws SQLException {
		for (int i = 0; i < columns.length; i++) {
			if (columns[i].equalsIgnoreCase(columnName)) return(i + 1);
		}
		throw new SQLException("No such columnname: " + columnName);
	}

	/**
	 * Moves the cursor to the first row in this ResultSet object.
	 *
	 * @return true if the cursor is on a valid row; false if there are no rows
	 *         in the result set
	 * @throws SQLException - if a database access error occurs or the result
	 *         set type is TYPE_FORWARD_ONLY
	 */
	public boolean first() throws SQLException {
		return(absolute(1));
	}

	public Array 	getArray(int i) { return(null); }
	public Array 	getArray(String colName) { return(null); }
	public InputStream 	getAsciiStream(int columnIndex) { return(null); }
	public InputStream 	getAsciiStream(String columnName) { return(null); }
	public BigDecimal 	getBigDecimal(int columnIndex) { return(null); }
	public BigDecimal 	getBigDecimal(int columnIndex, int scale) { return(null); }
	public BigDecimal 	getBigDecimal(String columnName) { return(null); }
	public BigDecimal 	getBigDecimal(String columnName, int scale) { return(null); }
	public InputStream 	getBinaryStream(int columnIndex) { return(null); }
	public InputStream 	getBinaryStream(String columnName) { return(null); }
	public Blob 	getBlob(int i) { return(null); }
	public Blob 	getBlob(String colName) { return(null); }

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a boolean in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is false
	 * @throws SQLException if there is no such column
	 */
	public boolean getBoolean(int columnIndex) throws SQLException{
		return((Boolean.valueOf(getString(columnIndex))).booleanValue());
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a boolean in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is false
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public boolean getBoolean(String columnName) throws SQLException {
		return(getBoolean(findColumn(columnName)));
	}

	public byte 	getByte(int columnIndex) { return((byte)0); }
	public byte 	getByte(String columnName) { return((byte)0); }
	public byte[] 	getBytes(int columnIndex) { return(null); }
	public byte[] 	getBytes(String columnName) { return(null); }
	public Reader 	getCharacterStream(int columnIndex) { return(null); }
	public Reader 	getCharacterStream(String columnName) { return(null); }
	public Clob 	getClob(int i) { return(null); }
	public Clob 	getClob(String colName) { return(null); }
	public int 	getConcurrency() { return(-1); }
	public String 	getCursorName() { return(null); }
	public java.sql.Date 	getDate(int columnIndex) { return(null); }
	public java.sql.Date 	getDate(int columnIndex, Calendar cal) { return(null); }
	public java.sql.Date 	getDate(String columnName) { return(null); }
	public java.sql.Date 	getDate(String columnName, Calendar cal) { return(null); }

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a double in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if there is no such column
	 */
	public double getDouble(int columnIndex) throws SQLException {
		double ret = 0.0;
		try {
			ret = Double.parseDouble(getString(columnIndex));
		} catch (NumberFormatException e) {
			// ignore, return the default: 0
		}
		// do not catch SQLException for it is declared to be thrown

		return(ret);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a double in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public double getDouble(String columnName) throws SQLException {
		return(getDouble(findColumn(columnName)));
	}

	/**
	 * Retrieves the fetch direction for this ResultSet object.
	 * <b>currently not implemented</b>
	 *
	 * @return the current fetch direction for this ResultSet object
	 */
	public int getFetchDirection() {
		return(-1);
	}

	/**
	 * Retrieves the fetch size for this ResultSet object.
	 *
	 * @return the current fetch size for this ResultSet object
	 */
	public int getFetchSize() {
		return(-1);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a float in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if there is no such column
	 */
	public float getFloat(int columnIndex) throws SQLException {
		float ret = 0;	// note: relaxing by compiler here
		try {
			ret = Float.parseFloat(getString(columnIndex));
		} catch (NumberFormatException e) {
			// ignore, return the default: 0
		}
		// do not catch SQLException for it is declared to be thrown

		return(ret);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a float in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public float getFloat(String columnName) throws SQLException {
		return(getFloat(findColumn(columnName)));
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as an int in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if there is no such column
	 */
	public int getInt(int columnIndex) throws SQLException {
		int ret = 0;
		try {
			ret = Integer.parseInt(getString(columnIndex));
		} catch (NumberFormatException e) {
			// ignore, return the default: 0
		}
		// do not catch SQLException for it is declared to be thrown

		return(ret);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as an int in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public int getInt(String columnName) throws SQLException {
		return(getInt(findColumn(columnName)));
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a long in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if there is no such column
	 */
	public long getLong(int columnIndex) throws SQLException {
		long ret = 0;
		try {
			ret = Long.parseLong(getString(columnIndex));
		} catch (NumberFormatException e) {
			// ignore, return the default: 0
		}
		// do not catch SQLException for it is declared to be thrown

		return(ret);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a long in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public long getLong(String columnName) throws SQLException {
		return(getLong(findColumn(columnName)));
	}

	/**
	 * Retrieves the number, types and properties of this ResultSet object's
	 * columns.
	 *
	 * @return the description of this ResultSet object's columns
	 */
	public ResultSetMetaData getMetaData() {
		// return inner class which implements the ResultSetMetaData interface
		return(new ResultSetMetaData() {
			/**
			 * Returns the number of columns in this ResultSet object.
			 *
			 * @returns the number of columns
			 */
			public int getColumnCount() {
				return(columns.length);
			}

			public boolean isAutoIncrement(int column) {return(false);}

			/**
			 * Indicates whether a column's case matters. This holds for all
			 * columns in MonetDB resultsets since the mapping is done case
			 * sensitive. Therefore this method will always return true.
			 *
			 * @returns true
			 */
			public boolean isCaseSensitive(int column) {
				return(true);
			}

			public boolean isSearchable(int column) {return(false);}
			public boolean isCurrency(int column) {return(false);}
			public int isNullable(int column) {return(columnNullableUnknown);}
			public boolean isSigned(int column) {return(false);}
			public int getColumnDisplaySize(int column) {return(-1);}

			/**
			 * Gets the designated column's suggested title for use in printouts
			 * and displays. This is currently equal to getColumnName().
			 *
			 * @param column the first column is 1, the second is 2, ...
			 * @return the suggested column title
			 * @throws SQLException if there is no such column
			 */
			public String getColumnLabel(int column) throws SQLException {
				return(getColumnName(column));
			}

			/**
			 * Gets the designated column's name
			 *
			 * @param column the first column is 1, the second is 2, ...
			 * @return the column name
			 * @throws SQLException if there is no such column
			 */
			public String getColumnName(int column) throws SQLException {
				try {
					return(columns[column - 1]);
				} catch (IndexOutOfBoundsException e) {
					throw new SQLException("No such column " + column);
				}
			}

			public String getSchemaName(int column) {return(null);}
			public int getPrecision(int column) {return(-1);}
			public int getScale(int column) {return(-1);}
			public String getTableName(int column) {return(null);}
			public String getCatalogName(int column) {return(null);}

			/**
			 * Retrieves the designated column's SQL type.
			 *
			 * @param column the first column is 1, the second is 2, ...
			 * @return SQL type from java.sql.Types
			 * @throws SQLException if there is no such column
			 */
			public int getColumnType(int column) throws SQLException {
				String type = getColumnTypeName(column);

				// match the column type on a java.sql.Types constant
				if ("table".equals(type)) {
					return(Types.ARRAY);
				} else if ("boolean".equals(type) || "bool".equals(type)) {
					return(Types.BOOLEAN);
				} else if ("ubyte".equals(type)) {
					return(Types.CHAR);
				} else if ("char".equals(type) || "character".equals(type)) {
					return(Types.CHAR);
				} else if ("varchar".equals(type)) {
					return(Types.VARCHAR);
				} else if ("text".equals(type) || "tinytext".equals(type)) {
					return(Types.LONGVARCHAR);
				} else if ("string".equals(type)) {
					return(Types.LONGVARCHAR);
				} else if ("tinyint".equals(type) || "smallint".equals(type)) {
					return(Types.INTEGER);
				} else if ("mediumint".equals(type)) {
					return(Types.INTEGER);
				} else if ("oid".equals(type)) {
					return(Types.OTHER);
				} else if ("int".equals(type) || "integer".equals(type)) {
					return(Types.INTEGER);
				} else if ("bigint".equals(type)) {
					return(Types.INTEGER);
				} else if ("number".equals(type)) {
					return(Types.NUMERIC);
				} else if ("decimal".equals(type)) {
					return(Types.DECIMAL);
				} else if ("numeric".equals(type)) {
					return(Types.NUMERIC);
				} else if ("float".equals(type)) {
					return(Types.FLOAT);
				} else if ("double".equals(type)) {
					return(Types.DOUBLE);
				} else if ("real".equals(type)) {
					return(Types.REAL);
				} else if ("int".equals(type)) {
					return(Types.INTEGER);
				} else if ("month_interval".equals(type)) {
					return(Types.OTHER);
				} else if ("sec_interval".equals(type)) {
					return(Types.OTHER);
				} else if ("date".equals(type)) {
					return(Types.DATE);
				} else if ("time".equals(type)) {
					return(Types.TIME);
				} else if ("datetime".equals(type) || "timestamp".equals(type)) {
					return(Types.TIMESTAMP);
				} else if ("blob".equals(type)) {
					return(Types.BLOB);
				} else {
					// this should not be able to happen
					// do not assert, since maybe feature versions introduce
					// new types
					return(Types.OTHER);
				}
			}

			/**
			 * Retrieves the designated column's database-specific type name.
			 *
			 * @param column the first column is 1, the second is 2, ...
			 * @return type name used by the database. If the column type is a
			 *         user-defined type, then a fully-qualified type name is
			 *         returned.
			 * @throws SQLException if there is no such column
			 */
			public String getColumnTypeName(int column) throws SQLException {
				try {
					return(types[column - 1]);
				} catch (IndexOutOfBoundsException e) {
					throw new SQLException("No such column " + column);
				}
			}

			public boolean isReadOnly(int column) {return(false);}
			public boolean isWritable(int column) {return(false);}
			public boolean isDefinitelyWritable(int column) {return(false);}
			public String getColumnClassName(int column) {return(null);}
		});
	}

	public Object 	getObject(int columnIndex) { return(null); }
	public Object 	getObject(int i, Map map) { return(null); }
	public Object 	getObject(String columnName) { return(null); }
	public Object 	getObject(String colName, Map map) { return(null); }
	public Ref 	getRef(int i) { return(null); }
	public Ref 	getRef(String colName) { return(null); }
	public int 	getRow() { return(-1); }

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a short in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if there is no such column
	 */
	public short getShort(int columnIndex) throws SQLException {
		short ret = 0;	// note: relaxing by compiler here
		try {
			ret = Short.parseShort(getString(columnIndex));
		} catch (NumberFormatException e) {
			// ignore, return the default: 0
		}
		// do not catch SQLException for it is declared to be thrown

		return(ret);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a short in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is 0
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public short getShort(String columnName) throws SQLException {
		return(getShort(findColumn(columnName)));
	}

	/**
	 * Retrieves the Statement object that produced this ResultSet object. If
	 * the result set was generated some other way, such as by a
	 * DatabaseMetaData method, this method returns null.
	 *
	 * @return the Statment object that produced this ResultSet object or null
	 *         if the result set was produced some other way
	 */
	public Statement getStatement() {
		return(statement);
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a String in the Java programming language.
	 *
	 * @param columnIndex the first column is 1, the second is 2, ...
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is null
	 * @throws SQLException if there is no such column
	 */
	public String getString(int columnIndex) throws SQLException {
		// note: all current getters use the string getter in the end
		// in the future this might change, and the lastColumnRead must
		// be updated for the wasNull command to work properly!!!
		try {
			String ret = result[columnIndex - 1];
			lastColumnRead = columnIndex - 1;
			return(ret);
		} catch (IndexOutOfBoundsException e) {
			throw new SQLException("No such column " + columnIndex);
		}
	}

	/**
	 * Retrieves the value of the designated column in the current row of this
	 * ResultSet object as a String in the Java programming language.
	 *
	 * @param columnName the SQL name of the column
	 * @return the column value; if the value is SQL NULL, the value returned
	 *         is null
	 * @throws SQLException if the ResultSet object does not contain columnName
	 */
	public String getString(String columnName) throws SQLException {
		return(getString(findColumn(columnName)));
	}

	public Time 	getTime(int columnIndex) { return(null); }
	public Time 	getTime(int columnIndex, Calendar cal) { return(null); }
	public Time 	getTime(String columnName) { return(null); }
	public Time 	getTime(String columnName, Calendar cal) { return(null); }
	public Timestamp 	getTimestamp(int columnIndex) { return(null); }
	public Timestamp 	getTimestamp(int columnIndex, Calendar cal) { return(null); }
	public Timestamp 	getTimestamp(String columnName) { return(null); }
	public Timestamp 	getTimestamp(String columnName, Calendar cal) { return(null); }
	public int 	getType() { return(-1); }
	public InputStream 	getUnicodeStream(int columnIndex) { return(null); }
	public InputStream 	getUnicodeStream(String columnName) { return(null); }
	public URL 	getURL(int columnIndex) { return(null); }
	public URL 	getURL(String columnName) { return(null); }
	public SQLWarning 	getWarnings() { return(null); }
	public void 	insertRow() {}

	/**
	 * Retrieves whether the cursor is after the last row in this ResultSet
	 * object.
	 *
	 * @return true if the cursor is after the last row; false if the cursor is
	 *         at any other position or the result set contains no rows
	 */
	public boolean isAfterLast() {
		return(curRow == tupleCount + 1);
	}

	/**
	 * Retrieves whether the cursor is before the first row in this ResultSet
	 * object.
	 *
	 * @return true if the cursor is before the first row; false if the cursor
	 *         is at any other position or the result set contains no rows
	 */
	public boolean isBeforeFirst() {
		return(curRow == 0);
	}

	/**
	 * Retrieves whether the cursor is on the first row of this ResultSet
	 * object.
	 *
	 * @return true if the cursor is on the first row; false otherwise
	 */
	public boolean isFirst() {
		return(curRow == 1);
	}

	/**
	 * Retrieves whether the cursor is on the last row of this ResultSet object.
	 *
	 * @return true if the cursor is on the last row; false otherwise
	 */
	public boolean isLast() {
		return(curRow == tupleCount);
	}

	/**
	 * Moves the cursor to the last row in this ResultSet object.
	 *
	 * @return true if the cursor is on a valid row; false if there are no rows
	 *         in the result set
	 * @throws SQLException if a database access error occurs or the result set
	 *         type is TYPE_FORWARD_ONLY
	 */
	public boolean last() throws SQLException {
		return(absolute(-1));
	}

	public void 	moveToCurrentRow() {}
	public void 	moveToInsertRow() {}

	/**
	 * Moves the cursor down one row from its current position. A ResultSet
	 * cursor is initially positioned before the first row; the first call to
	 * the method next makes the first row the current row; the second call
	 * makes the second row the current row, and so on.
	 * <br /><br />
	 * If an input stream is open for the current row, a call to the method
	 * next will implicitly close it. A ResultSet object's warning chain is
	 * cleared when a new row is read.
	 *
	 * @return true if the new current row is valid; false if there are no
	 *         more rows
	 * @throws SQLException if a database access error occurs or ResultSet is
	 *         closed
	 */
	public boolean next() throws SQLException {
		return(relative(1));
	}

	/**
	 * Moves the cursor to the previous row in this ResultSet object.
	 *
	 * @return true if the cursor is on a valid row; false if it is off
	 *         the result set
	 * @throws SQLException if a database access error occurs or ResultSet is
	 *         closed or the result set type is TYPE_FORWARD_ONLY
	 */
	public boolean previous() throws SQLException {
		return(relative(-1));
	}

	public void refreshRow() {}

	/**
	 * Moves the cursor a relative number of rows, either positive or negative.
	 * Attempting to move beyond the first/last row in the result set positions
	 * the cursor before/after the the first/last row. Calling relative(0) is
	 * valid, but does not change the cursor position.
	 * <br /><br />
	 * Note: Calling the method relative(1) is identical to calling the method
	 * next() and calling the method relative(-1) is identical to calling the
	 * method previous().
	 *
	 * @param rows an int specifying the number of rows to move from the current
	 *        row; a positive number moves the cursor forward; a negative number
	 *        moves the cursor backward
	 * @return true if the cursor is on a row; false otherwise
	 * @throws SQLException if a database access error occurs, there is no current
	 *         row, or the result set type is TYPE_FORWARD_ONLY
	 */
	public boolean relative(int rows) throws SQLException {
		return(absolute(curRow + rows));
	}

	public boolean 	rowDeleted() { return(false); }
	public boolean 	rowInserted() { return(false); }
	public boolean 	rowUpdated() { return(false); }
	public void 	setFetchDirection(int direction) {}
	public void 	setFetchSize(int rows) {}
	public void 	updateArray(int columnIndex, Array x) {}
	public void 	updateArray(String columnName, Array x) {}
	public void 	updateAsciiStream(int columnIndex, InputStream x, int length) {}
	public void 	updateAsciiStream(String columnName, InputStream x, int length) {}
	public void 	updateBigDecimal(int columnIndex, BigDecimal x) {}
	public void 	updateBigDecimal(String columnName, BigDecimal x) {}
	public void 	updateBinaryStream(int columnIndex, InputStream x, int length) {}
	public void 	updateBinaryStream(String columnName, InputStream x, int length) {}
	public void 	updateBlob(int columnIndex, Blob x) {}
	public void 	updateBlob(String columnName, Blob x) {}
	public void 	updateBoolean(int columnIndex, boolean x) {}
	public void 	updateBoolean(String columnName, boolean x) {}
	public void 	updateByte(int columnIndex, byte x) {}
	public void 	updateByte(String columnName, byte x) {}
	public void 	updateBytes(int columnIndex, byte[] x) {}
	public void 	updateBytes(String columnName, byte[] x) {}
	public void 	updateCharacterStream(int columnIndex, Reader x, int length) {}
	public void 	updateCharacterStream(String columnName, Reader reader, int length) {}
	public void 	updateClob(int columnIndex, Clob x) {}
	public void 	updateClob(String columnName, Clob x) {}
	public void 	updateDate(int columnIndex, java.sql.Date x) {}
	public void 	updateDate(String columnName, java.sql.Date x) {}
	public void 	updateDouble(int columnIndex, double x) {}
	public void 	updateDouble(String columnName, double x) {}
	public void 	updateFloat(int columnIndex, float x) {}
	public void 	updateFloat(String columnName, float x) {}
	public void 	updateInt(int columnIndex, int x) {}
	public void 	updateInt(String columnName, int x) {}
	public void 	updateLong(int columnIndex, long x) {}
	public void 	updateLong(String columnName, long x) {}
	public void 	updateNull(int columnIndex) {}
	public void 	updateNull(String columnName) {}
	public void 	updateObject(int columnIndex, Object x) {}
	public void 	updateObject(int columnIndex, Object x, int scale) {}
	public void 	updateObject(String columnName, Object x) {}
	public void 	updateObject(String columnName, Object x, int scale) {}
	public void 	updateRef(int columnIndex, Ref x) {}
	public void 	updateRef(String columnName, Ref x) {}
	public void 	updateRow() {}
	public void 	updateShort(int columnIndex, short x) {}
	public void 	updateShort(String columnName, short x) {}
	public void 	updateString(int columnIndex, String x) {}
	public void 	updateString(String columnName, String x) {}
	public void 	updateTime(int columnIndex, Time x) {}
	public void 	updateTime(String columnName, Time x) {}
	public void 	updateTimestamp(int columnIndex, Timestamp x) {}
	public void 	updateTimestamp(String columnName, Timestamp x) {}

	/**
	// Chapter 14.2.3.3 Sun JDBC 3.0 Specification
	 * Reports whether the last column read had a value of SQL NULL. Note that
	 * you must first call one of the getter methods on a column to try to read
	 * its value and then call the method wasNull to see if the value read was
	 * SQL NULL.
	 *
	 * @returns true if the last column value read was SQL NULL and false
	 *          otherwise
	 */
	public boolean wasNull() {
		return(lastColumnRead != -1 ? result[lastColumnRead] == null : false);
	}

	//== end methods of interface ResultSet

	protected void finalize() {
		close();
	}
}
