package com.absmartly.sdk.cache;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.absmartly.sdk.json.ContextData;
import com.absmartly.sdk.json.PublishEvent;

public final class SqliteCache extends AbstractCache {

	private Connection connection;
	private final ReentrantLock cacheLock = new ReentrantLock();

	public void writeEvent(PublishEvent event) {
		cacheLock.lock();
		PreparedStatement statement = null;
		try {
			statement = getConnection().prepareStatement("insert into events (event) values (?)");
			statement.setString(1,
					this.serializeEvent(event));
			statement.execute();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			closeResources(null, statement, null);
		}
		cacheLock.unlock();
	}

	public List<PublishEvent> retrieveEvents() {
		cacheLock.lock();
		Statement statement = null;
		ResultSet rs = null;
		List<PublishEvent> events = new ArrayList<PublishEvent>();
		try {
			statement = getConnection().createStatement();
			rs = statement.executeQuery("select * from events");
			while (rs.next()) {
				String eventStr = rs.getString("event");

				events.add(this.deserializeEvent(eventStr));
			}

			statement.execute("DELETE FROM events");
		} catch (SQLException exception) {
			throw new RuntimeException(exception);
		} finally {
			closeResources(rs, statement, null);
		}
		cacheLock.unlock();
		return events;
	}

	@Override
	public void writeContextData(ContextData contextData) {
		PreparedStatement statement = null;
		try {
			Statement deleteStatement = getConnection().createStatement();
			deleteStatement.execute("DELETE FROM context");
			deleteStatement.close();

			statement = getConnection().prepareStatement("insert into context (context) values (?)");
			statement.setString(1,
					this.serializeContext(contextData));
			statement.execute();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			closeResources(null, statement, null);
		}
	}

	@Override
	public ContextData getContextData() {
		Statement statement = null;
		ResultSet rs = null;
		ContextData contextData = null;
		try {
			statement = getConnection().createStatement();
			rs = statement.executeQuery("select * from context");
			if (rs.next()) {
				String eventStr = rs.getString("context");

				contextData = this.deserializeContext(eventStr);
			}
		} catch (SQLException exception) {
			throw new RuntimeException(exception);
		} finally {
			closeResources(rs, statement, null);
		}

		return contextData;
	}

	private Connection getConnection() throws SQLException {
		if (this.connection == null) {
			this.connection = DriverManager.getConnection("jdbc:sqlite:absmarly.db");
			setupDatabase();
		}
		return this.connection;
	}

	private void setupDatabase() throws SQLException {
		Statement statement = null;
		try {
			statement = getConnection().createStatement();
			statement.executeUpdate(
					"create table if not exists  events (id INTEGER PRIMARY KEY AUTOINCREMENT, event text)");

			statement.executeUpdate(
					"create table if not exists  context (id INTEGER PRIMARY KEY AUTOINCREMENT, context text)");
		} finally {
			closeResources(null, statement, null);
		}
	}

	private void closeResources(ResultSet rs, Statement statement, Connection conn) {
		try {
			if (rs != null)
				rs.close();
			if (statement != null)
				statement.close();
			if (conn != null)
				conn.close();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

}
