/**
 * This file is part of Erjang - A JVM-based Erlang VM
 *
 * Copyright (c) 2009 by Trifork
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
 **/

package erjang.driver.efile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectableChannel;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Lock;

import sun.security.util.Cache.EqualByteArray;

import erjang.EBinary;
import erjang.EObject;
import erjang.EPort;
import erjang.ERT;
import erjang.ERef;
import erjang.EString;
import erjang.NotImplemented;
import erjang.driver.EAsync;
import erjang.driver.EDriverEvent;
import erjang.driver.EDriverInstance;
import erjang.driver.IO;

/**
 * Java does nor support proper non-blocking IO for files (i.e., you cannot
 * select on a file). Select is only supported for sockets (and server sockets).
 */
public class EFile extends EDriverInstance {

	/**
	 * 
	 */
	private static final Charset ISO_8859_1 = Charset.forName("ISO_8859_1");
	private final EString command;
	private FileChannel fd;
	private EPort port;
	private EPort key;
	private int flags;
	private TimerState timer_state;
	private FileAsync invoke;
	private Queue<FileAsync> cq;
	private int level;
	private Lock q_mtx;
	private int write_buffered;
	private int write_bufsize;

	public int posix_errno;
	public boolean write_error;
	private long write_delay;

	/**
	 * 
	 */
	private final class WriteAsync extends FileAsync {
		Lock q_mtx;
		int size, free_size, reply_size;

		/**
		 * @param xreplySize
		 * @param efile
		 */
		private WriteAsync(boolean reply, int reply_size) {

			EFile efile = EFile.this;

			super.command = FILE_WRITE;
			super.fd = efile.fd;
			super.flags = efile.flags;
			super.level = 1;

			this.q_mtx = efile.q_mtx;
			this.size = efile.write_buffered;

			super.reply = reply;

			this.free_size = 0;
			this.reply_size = reply_size;
		}

		/** invoke_writev */
		@Override
		public void async() {
			int size;

			boolean segment = again && this.size >= 2 * FILE_SEGMENT_WRITE;
			if (segment) {
				size = FILE_SEGMENT_WRITE;
			} else {
				size = this.size;
			}

			q_mtx.lock();
			ByteBuffer[] iov0 = driver_peekq();
			if (iov0 != null) {

				// copy the buffers
				ByteBuffer[] iov = iov0.clone();
				q_mtx.unlock();

				// figure out how much data we have available for writing
				long p = 0;
				int iovcnt = 0;
				while (p < size && iovcnt < iov.length) {
					p += iov[iovcnt++].remaining();
				}

				if (iov.length > 0) {
					// What is this good for?
					assert iov[iovcnt - 1].limit() > p - size;

					if ((flags & EFILE_COMPRESSED) == EFILE_COMPRESSED) {
						for (int i = 0; i < iovcnt; i++) {
							try {
								free_size += IO.gzwrite(fd, iov[i]);
								super.result_ok = true;
							} catch (IOException e) {
								posix_errno = IO.exception_to_posix_code(e);
								super.result_ok = false;
							}
						}
					} else {
						try {

							free_size += IO.writev(fd, iov);
							result_ok = true;
						} catch (IOException e) {
							posix_errno = IO.exception_to_posix_code(e);
							super.result_ok = false;

						}
					}
				} else if (iov.length == 0) {
					result_ok = true;
				}
			} else {
				q_mtx.unlock();
				posix_errno = Posix.EINVAL;
				result_ok = false;
			}

			if (!result_ok) {
				again = false;
			} else if (!segment) {
				again = false;
			}
		}

		// called from the DriverTask
		@Override
		public void ready() {
			if (reply) {
				if (!result_ok) {
					reply_posix_error(posix_errno);
				} else {
					reply_Uint(reply_size);
				}

			} else {
				if (!result_ok) {
					EFile.this.write_error = true;
					EFile.this.posix_errno = posix_errno;
				}

			}
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see erjang.driver.efile.FileAsync#deq_free_size()
		 */
		@Override
		public void deq_free_size() {
			driver_deq(free_size);
		}
	}

	/**
	 * 
	 */
	public enum TimerState {
		IDLE, AGAIN, WRITE
	}

	/*
	 * This structure contains date and time.
	 */
	public static class Time {
		short year; /* (4 digits). */
		short month; /* (1..12). */
		short day; /* (1..31). */
		short hour; /* (0..23). */
		short minute; /* (0..59). */
		short second; /* (0..59). */
	}

	/** stat info about file */
	public static class Info {
		int size_low; /* Size of file, lower 32 bits.. */
		int size_high; /* Size of file, higher 32 bits. */
		int type; /* Type of file -- one of FT_*. */
		int access; /* Access to file -- one of FA_*. */
		int mode; /* Access permissions -- bit field. */
		int links; /* Number of links to file. */
		int major_device; /* Major device or file system. */
		int minor_device; /* Minor device (for devices). */
		int inode; /* Inode number. */
		int uid; /* User id of owner. */
		int gid; /* Group id of owner. */
		Time accessTime; /* Last time the file was accessed. */
		Time modifyTime; /* Last time the file was modified. */
		Time cTime; /* Creation time (Windows) or last */
	}

	/*
	 * Open modes for efile_openfile().
	 */
	public static final int EFILE_MODE_READ = 1;
	public static final int EFILE_MODE_WRITE = 2; /*
												 * Implies truncating file when
												 * used alone.
												 */
	public static final int EFILE_MODE_READ_WRITE = 3;
	public static final int EFILE_MODE_APPEND = 4;
	public static final int EFILE_COMPRESSED = 8;
	public static final int EFILE_NO_TRUNCATE = 16; /*
													 * Special for reopening on
													 * VxWorks
													 */

	/*
	 * Seek modes for efile_seek().
	 */
	public static final int EFILE_SEEK_SET = 0;
	public static final int EFILE_SEEK_CUR = 1;
	public static final int EFILE_SEEK_END = 2;

	/*
	 * File types returned by efile_fileinfo().
	 */
	public static final int FT_DEVICE = 1;
	public static final int FT_DIRECTORY = 2;
	public static final int FT_REGULAR = 3;
	public static final int FT_SYMLINK = 4;
	public static final int FT_OTHER = 5;

	/*
	 * Access attributes returned by efile_fileinfo() (the bits can be ORed
	 * together).
	 */
	public static final int FA_NONE = 0;
	public static final int FA_WRITE = 1;
	public static final int FA_READ = 2;

	/* commands sent via efile_output(v) */

	public static final int FILE_OPEN = 1; /* Essential for startup */
	public static final int FILE_READ = 2;
	public static final int FILE_LSEEK = 3;
	public static final int FILE_WRITE = 4;
	public static final int FILE_FSTAT = 5; /* Essential for startup */
	public static final int FILE_PWD = 6; /* Essential for startup */
	public static final int FILE_READDIR = 7; /* Essential for startup */
	public static final int FILE_CHDIR = 8;
	public static final int FILE_FSYNC = 9;
	public static final int FILE_MKDIR = 10;
	public static final int FILE_DELETE = 11;
	public static final int FILE_RENAME = 12;
	public static final int FILE_RMDIR = 13;
	public static final int FILE_TRUNCATE = 14;
	public static final int FILE_READ_FILE = 15; /* Essential for startup */
	public static final int FILE_WRITE_INFO = 16;
	public static final int FILE_LSTAT = 19;
	public static final int FILE_READLINK = 20;
	public static final int FILE_LINK = 21;
	public static final int FILE_SYMLINK = 22;
	public static final int FILE_CLOSE = 23;
	public static final int FILE_PWRITEV = 24;
	public static final int FILE_PREADV = 25;
	public static final int FILE_SETOPT = 26;
	public static final int FILE_IPREAD = 27;
	public static final int FILE_ALTNAME = 28;
	public static final int FILE_READ_LINE = 29;

	/* Return codes */

	public static final byte FILE_RESP_OK = 0;
	/**
	 * 
	 */
	private static final byte[] FILE_RESP_OK_HEADER = new byte[]{ FILE_RESP_OK };
	public static final byte FILE_RESP_ERROR = 1;
	public static final byte FILE_RESP_DATA = 2;
	public static final byte FILE_RESP_NUMBER = 3;
	public static final byte FILE_RESP_INFO = 4;
	public static final byte FILE_RESP_NUMERR = 5;
	public static final byte FILE_RESP_LDATA = 6;
	public static final byte FILE_RESP_N2DATA = 7;
	public static final byte FILE_RESP_EOF = 8;

	/* Options */

	public static final int FILE_OPT_DELAYED_WRITE = 0;
	public static final int FILE_OPT_READ_AHEAD = 1;

	/* IPREAD variants */

	public static final int IPREAD_S32BU_P32BU = 0;

	/* Limits */

	public static final int FILE_SEGMENT_READ = (256 * 1024);
	public static final int FILE_SEGMENT_WRITE = (256 * 1024);

	public static final int FILE_TYPE_DEVICE = 0;
	public static final int FILE_TYPE_DIRECTORY = 2;
	public static final int FILE_TYPE_REGULAR = 3;
	public static final int FILE_TYPE_SYMLINK = 4;

	public static final int FILE_ACCESS_NONE = 0;
	public static final int FILE_ACCESS_WRITE = 1;
	public static final int FILE_ACCESS_READ = 2;
	public static final int FILE_ACCESS_READ_WRITE = 3;
	private static final String SYS_INFO = null;

	private static final int THREAD_SHORT_CIRCUIT;

	/** initialize value of thread_short_circuit */
	static {
		String buf = System.getenv("ERL_EFILE_THREAD_SHORT_CIRCUIT");
		if (buf == null) {
			THREAD_SHORT_CIRCUIT = 0;
		} else {
			THREAD_SHORT_CIRCUIT = Integer.parseInt(buf);
		}
	}

	/**
	 * @param command
	 */
	public EFile(EString command) {
		this.command = command;

		this.fd = (FileChannel) null;
		this.key = port;
		this.flags = 0;
		this.invoke = null;
		this.cq = new LinkedList<FileAsync>();
		this.timer_state = TimerState.IDLE;
		// this.read_bufsize = 0;
		// this.read_binp = (ByteBuffer) null;
		// this.read_offset = 0;
		// this.read_size = 0;
		this.write_delay = 0L;
		this.write_bufsize = 0;
		// this.write_error = 0;
		this.q_mtx = driver_pdl_create();
		this.write_buffered = 0;

	}

	/**
	 * @param replySize
	 */
	public void reply_Uint(int value) {
		// TODO Auto-generated method stub

	}

	/**
	 * @param error
	 */
	public void reply_posix_error(int posix_errno) {
		ByteBuffer response = ByteBuffer.allocate(256);
		response.put(FILE_RESP_ERROR);
		String err = Posix.errno_id(posix_errno);
		IO.putstr(response, err, false);

		driver_output2(response, null);
	}

	@Override
	protected EObject call(int command, EObject data) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected void event(EDriverEvent event, Object eventData) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void flush() {
		int r = flush_write(null);
		assert (r == 0);
		cq_execute();
	}

	private abstract class SimpleFileAsync extends FileAsync {

		protected final File file;
		protected final String name;

		{
			level = 2;
			reply = true;
		}

		/**
		 * @param path
		 * @param cmd
		 */
		public SimpleFileAsync(byte command, String path) {
			this.command = command;
			this.name = path;
			this.file = new File(path);
		}

		@Override
		public final void async() {
			try {
				this.result_ok = false;
				run();
			} catch (OutOfMemoryError e) {
				posix_errno = Posix.ENOMEM;
			} catch (SecurityException e) {
				posix_errno = Posix.EPERM;
			} catch (Throwable e) {
				posix_errno = Posix.EUNKNOWN;
			}
		}

		/**
		 * This is what does the real operation
		 */
		protected abstract void run();

		public void ready() {
			reply(EFile.this);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see erjang.driver.EDriverInstance#outputv(java.nio.ByteBuffer[])
	 */
	@Override
	protected void outputv(ByteBuffer[] ev) {

		if (ev.length == 0 || ev[0].remaining() == 0) {
			reply_posix_error(Posix.EINVAL);
			return;
		}

		int command = ev[0].get();
		switch (command) {
		case FILE_READ_FILE: {
			if (ev.length > 1 && ev[0].hasRemaining()) {
				reply_posix_error(Posix.EINVAL);
				return;
			}

			ByteBuffer last = ev[ev.length - 1];
			final String name = IO.getstr(last, false);

			if (name.length() == 0) {
				reply_posix_error(Posix.ENOENT);
				return;
			}

			FileAsync d = new FileAsync() {
				private ByteBuffer binp = null;
				private long size = 0;
				{
					super.level = 2;
					super.again = true;
				}

				@Override
				public void async() {

					// first time only, initialize binp
					if (binp == null) {
						File file = new File(name);
						try {
							this.fd = new FileInputStream(file).getChannel();
						} catch (FileNotFoundException e) {
							this.again = false;
							result_ok = false;

							if (!file.exists() || !file.isFile())
								posix_errno = Posix.ENOENT;
							else if (!file.canRead())
								posix_errno = Posix.EPERM;
							else
								posix_errno = Posix.EUNKNOWN;

							this.again = false;
							return;
						}
						this.size = file.length();

						if (size > Integer.MAX_VALUE || size < 0) {
							result_ok = false;
							posix_errno = Posix.ENOMEM;
						} else {
							try {
								binp = ByteBuffer.allocate((int) size);
							} catch (OutOfMemoryError e) {
								result_ok = false;
								posix_errno = Posix.ENOMEM;
							}
						}
					}

					if (binp != null && binp.hasRemaining()) {

						try {
							int bytes = fd.read(binp);
							if (bytes == -1 && binp.hasRemaining()) {
								// urgh, file change size under our feet!
								result_ok = false;
								posix_errno = Posix.EIO;
							}

							if (binp.hasRemaining()) {
								again = true;
								return;
							} else {
								result_ok = true;
							}
						} catch (IOException e) {
							result_ok = false;
							posix_errno = IO.exception_to_posix_code(e);
						}

					}

					try {
						fd.close();
					} catch (IOException e) {
						result_ok = false;
						posix_errno = IO.exception_to_posix_code(e);
					}
					again = false;

				}

				@Override
				public void ready() {
					
					if (!result_ok) {
						reply_posix_error(posix_errno);
						return;
					}
					
					binp.flip();
					driver_output_binary(FILE_RESP_OK_HEADER, binp);
				}

			};

			cq_enq(d);
			break;
		}

		default:
			// undo the get() we did to find command
			ev[0].position(ev[0].position() - 1);
			output(flatten(ev));
		}

		cq_execute();

	}


	@Override
	protected void output(ByteBuffer data) {

		FileAsync d;
		byte cmd = data.get();
		switch (cmd) {
		case FILE_MKDIR: {
			d = new SimpleFileAsync(cmd, IO.strcpy(data)) {
				public void run() {
					result_ok = file.mkdir();
					if (!result_ok) {
						if (name.length() == 0) {
							posix_errno = Posix.ENOENT;
						} else if (file.exists()) {
							posix_errno = Posix.EEXIST;
						} else {
							posix_errno = Posix.EUNKNOWN;
						}
					}
				}

			};
			break;
		}

		case FILE_RMDIR: {
			d = new SimpleFileAsync(cmd, IO.strcpy(data)) {
				public void run() {
					result_ok = file.isDirectory() && file.delete();
					if (!result_ok) {
						if (!file.exists()) {
							posix_errno = Posix.ENOENT;
						} else if (Posix.isCWD(name, file)) {
							posix_errno = Posix.EINVAL;
						} else if (file.exists()) {
							posix_errno = Posix.EEXIST;
						} else {
							posix_errno = Posix.EUNKNOWN;
						}
					}
				}

			};
			break;
		}

		case FILE_DELETE: {
			d = new SimpleFileAsync(cmd, IO.strcpy(data)) {
				public void run() {
					result_ok = file.isFile() && file.delete();
					if (!result_ok) {
						if (!file.exists()) {
							posix_errno = Posix.ENOENT;
						} else if (file.isDirectory()) {
							posix_errno = Posix.EEXIST;
						} else {
							posix_errno = Posix.EUNKNOWN;
						}
					}
				}

			};
			break;
		}


		case FILE_PWD: {
			int drive = data.get();
			char dr = drive==0 ? '?' : (char)('A'+drive);
			
			d = new FileAsync() {

				private String pwd;

				{
					this.command = FILE_PWD;
					super.level = 2;
				}
				
				@Override
				public void async() {
					File pwd = Posix.getCWD();
					if (pwd.exists() && pwd.isDirectory()) {
						this.pwd = pwd.getAbsolutePath();
						result_ok = true;
					} else {
						result_ok = false;
						posix_errno = Posix.ENOENT;
					}
					again = false;
				}

				@Override
				public void ready() {
					if (!result_ok) {
						reply_posix_error(posix_errno);
					} else {
						ByteBuffer reply = ByteBuffer.allocate(1+pwd.length());
						reply.put(FILE_RESP_OK);
						IO.putstr(reply, pwd, false);
						driver_output2(reply, null);
					}
				}

			};
			break;
		}

		default:
			throw new NotImplemented("file_output cmd:" + ((int) cmd) + " "
					+ EBinary.make(data));
			/** ignore everything else - let the caller hang */
			// return;
		}

		if (d != null) {
			cq_enq(d);
		}

	}

	@Override
	protected void processExit(ERef monitor) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void readyAsync(EAsync data) {
		FileAsync d = (FileAsync) data;

		if (try_again(d))
			return;

		// do whatever for this kind of async job
		d.ready();

		if (write_buffered != 0 && timer_state == TimerState.IDLE) {
			timer_state = TimerState.WRITE;
			driver_set_timer(write_delay);
		}

		cq_execute();
	}

	/**
	 * @param d
	 * @return
	 */
	private boolean try_again(FileAsync d) {
		if (!d.again) {
			return false;
		}

		d.deq_free_size();

		if (timer_state != TimerState.IDLE) {
			driver_cancel_timer(port);
		}

		timer_state = TimerState.AGAIN;
		invoke = d;
		driver_set_timer(0);

		return true;
	}

	@Override
	protected void readyInput(SelectableChannel ch) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void readyOutput(SelectableChannel evt) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void timeout() {
		TimerState timer_state = this.timer_state;
		this.timer_state = TimerState.IDLE;
		switch (timer_state) {
		case IDLE:
			assert (false) : "timeout in idle state?";
			return;

		case AGAIN:
			assert (invoke != null);
			driver_async(invoke);
			break;

		case WRITE:
			int r = flush_write(null);
			assert (r == 0);
			cq_execute();
		}
	}

	/**
	 * @return
	 */
	private int flush_write(int[] errp) {
		int result;
		q_mtx.lock();
		try {
			if (this.write_buffered > 0) {
				result = async_write(null, false, 0);
			} else {
				result = 0;
			}
		} finally {
			q_mtx.unlock();
		}
		return result;
	}

	// // CQ OPERATIONS //

	/**
	 * @param errp
	 * @param reply
	 *            true if we should send a reply
	 * @param reply_size
	 *            value to send in reply
	 * @return
	 */
	private int async_write(int[] errp, boolean reply, int reply_size) {
		try {
			FileAsync cmd = new WriteAsync(reply, reply_size);
			cq_enq(cmd);
			write_buffered = 0;
			return 0;
		} catch (OutOfMemoryError e) {
			if (errp == null) {
				throw e;
			}
			errp[0] = Posix.ENOMEM;
			return -1;
		}
	}

	private void cq_enq(FileAsync d) {
		cq.add(d);
	}

	private FileAsync cq_deq() {
		return cq.poll();
	}

	private void cq_execute() {
		if (timer_state == TimerState.AGAIN)
			return;

		FileAsync d;
		if ((d = cq_deq()) == null)
			return;

		d.again = false; /* (SYS_INFO.async_threads == 0); */

		if (THREAD_SHORT_CIRCUIT >= level) {
			d.async();
			this.readyAsync(d);
		} else {
			driver_async(d);
		}
	}

	//

	void reply_buf(ByteBuffer buf) {
		ByteBuffer header = ByteBuffer.allocate(1 + 4 + 4);
		header.put(FILE_RESP_DATA);
		header.putLong(buf.position());
		driver_output2(header, buf);
	}

	/**
	 * 
	 */
	public void reply_ok() {
		// TODO Auto-generated method stub

	}

}