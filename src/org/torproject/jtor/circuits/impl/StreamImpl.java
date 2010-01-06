package org.torproject.jtor.circuits.impl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

import org.torproject.jtor.TorException;
import org.torproject.jtor.TorTimeoutException;
import org.torproject.jtor.circuits.Circuit;
import org.torproject.jtor.circuits.CircuitNode;
import org.torproject.jtor.circuits.Stream;
import org.torproject.jtor.circuits.cells.RelayCell;

public class StreamImpl implements Stream {
	private final static int STREAM_CONNECT_TIMEOUT = 20 * 1000;
	private final CircuitImpl circuit;
	private final int streamId;
	private final CircuitNode targetNode;
	private final TorInputStream inputStream;
	private final TorOutputStream outputStream;
	private boolean isClosed;
	private boolean relayEndReceived;
	private boolean relayConnectedReceived;
	private final Object waitConnectLock = new Object();

	StreamImpl(CircuitImpl circuit, CircuitNode targetNode, int streamId) {
		this.circuit = circuit;
		this.targetNode = targetNode;
		this.streamId = streamId;
		this.inputStream = new TorInputStream(this);
		this.outputStream = new TorOutputStream(this);
	}

	void addInputCell(RelayCell cell) {
		if(isClosed)
			return;
		if(cell.getRelayCommand() == RelayCell.RELAY_END) {
			synchronized(waitConnectLock) {
				relayEndReceived = true;
				inputStream.addEndCell(cell);
				waitConnectLock.notifyAll();
			}
		} else if(cell.getRelayCommand() == RelayCell.RELAY_CONNECTED) {
			synchronized(waitConnectLock) {
				relayConnectedReceived = true;
				waitConnectLock.notifyAll();
			}
		}
		else
			inputStream.addInputCell(cell);
	}

	public int getStreamId() {
		return streamId;
	}

	public Circuit getCircuit() {
		return circuit;
	}

	CircuitNode getTargetNode() {
		return targetNode;
	}

	public void close() {
		if(isClosed)
			return;
		isClosed = true;
		inputStream.close();
		outputStream.close();
		circuit.removeStream(this);

		if(!relayEndReceived) {
			final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_END);
			cell.putByte(RelayCell.REASON_DONE);
			circuit.sendRelayCellToFinalNode(cell);
		}
	}

	void openDirectory() {
		final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_BEGIN_DIR);
		circuit.sendRelayCellToFinalNode(cell);
		waitForRelayConnected();
	}

	void openExit(String target, int port) {
		final RelayCell cell = new RelayCellImpl(circuit.getFinalCircuitNode(), circuit.getCircuitId(), streamId, RelayCell.RELAY_BEGIN);
		cell.putString(target + ":"+ port);
		circuit.sendRelayCellToFinalNode(cell);
		waitForRelayConnected();
	}

	private void waitForRelayConnected() {
		final Date startWait = new Date();
		synchronized(waitConnectLock) {
			while(!relayConnectedReceived) {

				if(relayEndReceived)
					throw new TorException("RELAY_END cell received while waiting for RELAY_CONNECTED cell on stream id="+ streamId);

				if(hasStreamConnectTimedOut(startWait))
					throw new TorTimeoutException("Timeout waiting for RELAY_CONNECTED cell on stream id="+ streamId);

				try {
					waitConnectLock.wait(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new TorTimeoutException("Interrupted waiting for RELAY_CONNECTED cell on stream id="+ streamId);
				}
			}
		}
	}

	private static boolean hasStreamConnectTimedOut(Date startTime) {
		final Date now = new Date();
		final long diff = now.getTime() - startTime.getTime();
		return diff >= STREAM_CONNECT_TIMEOUT;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	public OutputStream getOutputStream() {
		return outputStream;
	}

	public String toString() {
		return "[Stream stream_id="+ streamId + " circuit="+ circuit +" ]";
	}
}
