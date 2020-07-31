package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * A Persistent Vector implementation representing 0-16 elements with an
 * arbitrary AVector prefix.
 * 
 * Design goals: - Allows fast access to most recently appended items - O(1)
 * append, equals - O(log n) access, update - O(log n) comparisons - Fast
 * computation of common prefix
 * 
 * Representation in bytes:
 * 
 * 0x80 ListVector tag byte VLC Long Length of list. >16 implies prefix must be
 * present. Low 4 bits specify N (0 means 16 in presence of prefix) [Ref]*N N
 * Elements with length Tail Ref using prefix hash (omitted if no prefix)
 *
 * @param <T> Type of vector elements
 */
public class VectorLeaf<T> extends AVector<T> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final VectorLeaf<?> EMPTY = new VectorLeaf(new Ref<?>[0]);

	/** Maximum size of a single ListVector before a tail is required */
	public static final int MAX_SIZE = Vectors.CHUNK_SIZE;

	private final Ref<T>[] items;
	private final Ref<AVector<T>> prefix;
	private final long count;

	VectorLeaf(Ref<T>[] items, Ref<AVector<T>> prefix, long count) {
		this.items = items;
		this.prefix = prefix;

		this.count = count;
	}

	VectorLeaf(Ref<T>[] items) {
		this(items, null, items.length);
	}

	/**
	 * Creates a ListVector with the given items
	 * 
	 * @param things
	 * @param offset
	 * @param length
	 * @return New ListVector
	 */
	@SuppressWarnings("unchecked")
	public static <T> VectorLeaf<T> create(T[] things, int offset, int length) {
		if (length == 0) return (VectorLeaf<T>) VectorLeaf.EMPTY;
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for ListVector: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			items[i] = Ref.create(things[i + offset]);
		}
		return new VectorLeaf<T>(items);
	}

	/**
	 * Creates a ListVector with the given items appended to the specified tail
	 * 
	 * @param things
	 * @param offset
	 * @param length
	 * @return The updated ListVector
	 */
	@SuppressWarnings("unchecked")
	public static <T> VectorLeaf<T> create(T[] things, int offset, int length, AVector<T> tail) {
		if (length == 0)
			throw new IllegalArgumentException("ListVector with tail cannot be created with zero head elements");
		if (length > Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Too many elements for ListVector: " + length);
		Ref<T>[] items = new Ref[length];
		for (int i = 0; i < length; i++) {
			items[i] = Ref.create(things[i + offset]);
		}
		return new VectorLeaf<T>(items, Ref.create(tail), tail.count() + length);
	}

	public static <T> VectorLeaf<T> create(T[] things) {
		return create(things, 0, things.length);
	}

	@Override
	public final AVector<T> toVector() {
		return this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> append(T value) {
		int localSize = items.length;
		if (localSize < Vectors.CHUNK_SIZE) {
			// extend storage array
			Ref<T>[] newItems = new Ref[localSize + 1];
			System.arraycopy(items, 0, newItems, 0, localSize);
			newItems[localSize] = Ref.create(value);

			if (localSize + 1 == Vectors.CHUNK_SIZE) {
				// need to extend to TreeVector
				VectorLeaf<T> chunk = new VectorLeaf<T>(newItems);
				if (!hasPrefix()) return chunk; // exactly one whole chunk
				return prefix.getValue().appendChunk(chunk);
			} else {
				// just grow current ListVector head
				return new VectorLeaf<T>(newItems, prefix, count + 1);
			}
		} else {
			// this must be a full single chunk already, so turn this into tail of new
			// ListVector
			AVector<T> newTail = this;
			return new VectorLeaf<T>(new Ref[] { Ref.create(value) }, Ref.create(newTail), count + 1);
		}
	}

	@Override
	public AVector<T> concat(ASequence<T> b) {
		// Maybe can optimise?
		long aLen = count();
		long bLen = b.count();
		AVector<T> result = this;
		long i = aLen;
		long end = aLen + bLen;
		while (i < end) {
			if ((i & Vectors.BITMASK) == 0) {
				int rn = Utils.checkedInt(Math.min(Vectors.CHUNK_SIZE, end - i));
				if (rn == Vectors.CHUNK_SIZE) {
					// we can append a whole chunk
					result = result.appendChunk((VectorLeaf<T>) b.subVector(i - aLen, rn));
					i += Vectors.CHUNK_SIZE;
					continue;
				}
			}
			// otherwise just append one-by-one
			result = result.append(b.get(i - aLen));
			i++;
		}
		return result;
	}

	@Override
	public AVector<T> appendChunk(VectorLeaf<T> chunk) {
		if (chunk.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append a chunk of size: " + chunk.count());

		if (this.count == 0) return chunk;
		if (this.hasPrefix()) {
			throw new IllegalArgumentException(
					"Can't append chunk to a ListVector with a tail (length = " + count + ")");
		}
		if (this.count != Vectors.CHUNK_SIZE)
			throw new IllegalArgumentException("Can't append chunk to a ListVector of size: " + this.count);
		return VectorTree.wrap2(chunk, this);
	}

	@Override
	public T get(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long ix = i - prefixLength();
		if (ix >= 0) {
			return items[(int) ix].getValue();
		} else {
			return prefix.getValue().get(i);
		}
	}

	@Override
	protected Ref<T> getElementRef(long i) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long ix = i - prefixLength();
		if (ix >= 0) {
			return items[(int) ix];
		} else {
			return prefix.getValue().getElementRef(i);
		}
	}

	@Override
	public AVector<T> assoc(long i, T value) {
		if ((i < 0) || (i >= count)) throw new IndexOutOfBoundsException("Index: " + i);
		long ix = i - prefixLength();
		if (ix >= 0) {
			T old = items[(int) ix].getValue();
			if (old == value) return this;
			Ref<T>[] newItems = items.clone();
			newItems[(int) ix] = Ref.create(value);
			return new VectorLeaf<T>(newItems, prefix, count);
		} else {
			AVector<T> tl = prefix.getValue();
			AVector<T> newTail = tl.assoc(i, value);
			if (tl == newTail) return this;
			return new VectorLeaf<T>(items, Ref.create(newTail), count);
		}
	}

	/**
	 * Reads a ListVector from the provided ByteBuffer Assumes the header byte is
	 * already read.
	 * 
	 * @param data
	 * @param count
	 * @return ListVector read from ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <T> VectorLeaf<T> read(ByteBuffer data, long count) throws BadFormatException {
		if (count < 0) throw new BadFormatException("Negative ListVector length");
		if (count == 0) return (VectorLeaf<T>) EMPTY;
		boolean prefixPresent = count > MAX_SIZE;

		int n = ((int) count) & 0xF;
		if (n == 0) {
			if (count > 16) throw new BadFormatException("ListVector not valid for size 0 mod 16: " + count);
			n = VectorLeaf.MAX_SIZE; // we know this must be true since zero already caught
		}

		Ref<T>[] items = (Ref<T>[]) new Ref<?>[n];
		for (int i = 0; i < n; i++) {
			Ref<T> ref = Format.readRef(data);
			items[i] = ref;
		}

		Ref<AVector<T>> tail = null;
		if (prefixPresent) {
			Object o = Format.read(data);
			if (!(o instanceof Ref)) throw new BadFormatException("Bad prefix format!");
			tail = (Ref<AVector<T>>) o;
		}

		return new VectorLeaf<T>(items, tail, count);
	}

	@Override
	public long count() {
		return count;
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.VECTOR);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		int ilength = items.length;
		boolean hasTail = hasPrefix();

		// count field
		bb = Format.writeVLCLong(bb, count);

		for (int i = 0; i < ilength; i++) {
			bb = items[i].write(bb);
		}

		if (hasTail) {
			bb = prefix.write(bb);
		}
		return bb;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for header, reasonable length, 33 bytes per element ref plus tail
		// ref
		return 1 + 9 + 33 * items.length + ((count > 16) ? 33 : 00);
	}

	/**
	 * Returns true if this ListVector has a prefix AVector.
	 * 
	 * @return true if this ListVector has a prefix, false otherwise
	 */
	public boolean hasPrefix() {
		return prefix != null;
	}

	public VectorLeaf<T> withPrefix(AVector<T> newPrefix) {
		if ((newPrefix == null) && !hasPrefix()) return this;
		long tc = (newPrefix == null) ? 0L : newPrefix.count();
		return new VectorLeaf<T>(items, (newPrefix == null) ? null : Ref.create(newPrefix), tc + items.length);
	}

	@Override
	public boolean isPacked() {
		return (!hasPrefix()) && (items.length == Vectors.CHUNK_SIZE);
	}

	@Override
	public ListIterator<T> listIterator() {
		return listIterator(0);
	}

	@Override
	public ListIterator<T> listIterator(long index) {
		return new ListVectorIterator(index);
	}

	/**
	 * Custom ListIterator for ListVector
	 */
	private class ListVectorIterator implements ListIterator<T> {
		ListIterator<T> prefixIterator;
		int pos;

		public ListVectorIterator(long index) {
			if (index < 0L) throw new NoSuchElementException();

			long tc = prefixLength();
			if (index >= tc) {
				// in the list head
				if (index > count) throw new NoSuchElementException();
				pos = (int) (index - tc);
				this.prefixIterator = (prefix == null) ? null : prefix.getValue().listIterator(tc);
			} else {
				// in the prefix
				pos = 0;
				this.prefixIterator = (prefix == null) ? null : prefix.getValue().listIterator(index);
			}
		}

		@Override
		public boolean hasNext() {
			if ((prefixIterator != null) && prefixIterator.hasNext()) return true;
			return pos < items.length;
		}

		@Override
		public T next() {
			if (prefixIterator != null) {
				if (prefixIterator.hasNext()) return prefixIterator.next();
			}
			return items[pos++].getValue();
		}

		@Override
		public boolean hasPrevious() {
			if (pos > 0) return true;
			if (prefixIterator != null) return prefixIterator.hasPrevious();
			return false;
		}

		@Override
		public T previous() {
			if (pos > 0) return items[--pos].getValue();

			if (prefixIterator != null) return prefixIterator.previous();
			throw new NoSuchElementException();
		}

		@Override
		public int nextIndex() {
			if ((prefixIterator != null) && prefixIterator.hasNext()) return prefixIterator.nextIndex();
			return Utils.checkedInt(prefixLength() + pos);
		}

		@Override
		public int previousIndex() {
			if (pos > 0) return Utils.checkedInt(prefixLength() + pos - 1);
			if (prefixIterator != null) return prefixIterator.previousIndex();
			return -1;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void set(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

		@Override
		public void add(T e) {
			throw new UnsupportedOperationException(Errors.immutable(this));
		}

	}

	public long prefixLength() {
		return count - items.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <K> void copyToArray(K[] arr, int offset) {
		int s = size();
		if (prefix != null) {
			prefix.getValue().copyToArray(arr, offset);
		}
		int ilen = items.length;
		for (int i = 0; i < ilen; i++) {
			K value = (K) items[i].getValue();
			;
			arr[offset + s - ilen + i] = value;
		}
	}

	@Override
	public long longIndexOf(Object o) {
		if (prefix != null) {
			long pi = prefix.getValue().longIndexOf(o);
			if (pi >= 0L) return pi;
		}
		for (int i = 0; i < items.length; i++) {
			if (Utils.equals(items[i].getValue(), o)) return (count - items.length + i);
		}
		return -1L;
	}

	@Override
	public long longLastIndexOf(Object o) {
		for (int i = items.length - 1; i >= 0; i--) {
			if (Utils.equals(items[i].getValue(), o)) return (count - items.length + i);
		}
		if (prefix != null) {
			long ti = prefix.getValue().longLastIndexOf(o);
			if (ti >= 0L) return ti;
		}
		return -1L;
	}

	@Override
	public void forEach(Consumer<? super T> action) {
		if (prefix != null) {
			prefix.getValue().forEach(action);

			for (Ref<T> r : items) {
				action.accept(r.getValue());
			}
		}
	}

	@Override
	public boolean anyMatch(Predicate<? super T> pred) {
		if ((prefix != null) && (prefix.getValue().anyMatch(pred))) return true;
		for (Ref<T> r : items) {
			if (pred.test(r.getValue())) return true;
		}
		return false;
	}

	@Override
	public boolean allMatch(Predicate<? super T> pred) {
		if ((prefix != null) && !(prefix.getValue().allMatch(pred))) return false;
		for (Ref<T> r : items) {
			if (!pred.test(r.getValue())) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> AVector<R> map(Function<? super T, ? extends R> mapper) {
		Ref<AVector<R>> newPrefix = (prefix == null) ? null : Ref.create(prefix.getValue().map(mapper));

		int ilength = items.length;
		Ref<R>[] newItems = (Ref<R>[]) new Ref[ilength];
		for (int i = 0; i < ilength; i++) {
			R r = mapper.apply(items[i].getValue());
			newItems[i] = Ref.create(r);
		}

		return (prefix == null) ? new VectorLeaf<R>(newItems) : new VectorLeaf<R>(newItems, newPrefix, count);
	}

	@Override
	public void visitElementRefs(Consumer<Ref<T>> f) {
		if (prefix != null) prefix.getValue().visitElementRefs(f);
		for (Ref<T> item : items) {
			f.accept(item);
		}
	}

	@Override
	public <R> R reduce(BiFunction<? super R, ? super T, ? extends R> func, R value) {
		if (prefix != null) value = prefix.getValue().reduce(func, value);
		int ilength = items.length;
		for (int i = 0; i < ilength; i++) {
			value = func.apply(value, items[i].getValue());
		}
		return value;
	}

	@Override
	public Spliterator<T> spliterator(long position) {
		return new ListVectorSpliterator(position);
	}

	private class ListVectorSpliterator implements Spliterator<T> {
		long pos = 0;

		public ListVectorSpliterator(long position) {
			if ((position < 0) || (position > count))
				throw new IllegalArgumentException(Errors.illegalPosition(position));
			this.pos = position;
		}

		@Override
		public boolean tryAdvance(Consumer<? super T> action) {
			if (pos >= count) return false;
			action.accept((T) get(pos++));
			return true;
		}

		@Override
		public Spliterator<T> trySplit() {
			long tlength = prefixLength();
			if (pos < tlength) {
				pos = tlength;
				return prefix.getValue().spliterator(pos);
			}
			return null;
		}

		@Override
		public long estimateSize() {
			return count;
		}

		@Override
		public int characteristics() {
			return Spliterator.IMMUTABLE | Spliterator.SIZED | Spliterator.SUBSIZED | Spliterator.ORDERED;
		}
	}

	@Override
	public boolean isCanonical() {
		if ((count > MAX_SIZE) && (prefix == null)) throw new Error("Invalid Listvector!");
		return true;
	}

	@Override
	public int getRefCount() {
		return items.length + (hasPrefix() ? 1 : 0);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		int ic = items.length;
		if (i < 0) throw new IndexOutOfBoundsException("Negative Ref index: " + i);
		if (i < ic) return (Ref<R>) items[i];
		if ((i == ic) && (prefix != null)) return (Ref<R>) prefix;
		throw new IndexOutOfBoundsException("Ref index out of range: " + i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public VectorLeaf<T> updateRefs(IRefFunction func) {
		Ref<?> newPrefix = (prefix == null) ? null : func.apply(prefix); // do this first for in-order traversal
		int ic = items.length;
		Ref<?>[] newItems = items;
		for (int i = 0; i < ic; i++) {
			Ref<?> current = items[i];
			Ref<?> newItem = func.apply(current);
			if (newItem!=current) {
				if (items==newItems) newItems=items.clone();
				newItems[i] = newItem;
			}
		}
		if ((items==newItems) && (prefix == newPrefix)) return this; // if no change, safe to return this
		return new VectorLeaf<T>((Ref<T>[]) newItems, (Ref<AVector<T>>) newPrefix, count);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object a) {
		if (!(a instanceof VectorLeaf)) return false;
		return equals((VectorLeaf<T>) a);
	}

	public boolean equals(VectorLeaf<T> v) {
		if (this == v) return true;
		if (this.count != v.count()) return false;
		if (!Utils.equals(this.prefix, v.prefix)) return false;
		for (int i = 0; i < items.length; i++) {
			if (!items[i].equalsValue(v.items[i])) return false;
		}
		return true;
	}

	@Override
	public long commonPrefixLength(AVector<T> b) {
		long n = count();
		if (this.equals(b)) return n;
		int il = items.length;
		long prefixLength = n - il;
		if (prefixLength > 0) {
			long prefixMatchLength = prefix.getValue().commonPrefixLength(b);
			if (prefixMatchLength < prefixLength) return prefixMatchLength; // matched segment entirely within prefix
		}
		// must have matched prefixLength at least
		long nn = Math.min(n, b.count()) - prefixLength; // number of extra elements to check
		for (int i = 0; i < nn; i++) {
			if (!items[i].equalsValue(b.getElementRef(prefixLength + i))) {
				return prefixLength + i;
			}
		}
		return prefixLength + nn;
	}

	@Override
	public VectorLeaf<T> getChunk(long offset) {
		if (prefix == null) {
			if (items.length != MAX_SIZE) throw new IllegalStateException("Can only get full chunk");
			if (offset != 0) throw new IndexOutOfBoundsException("Chunk offset must be zero");
			return this;
		} else {
			return prefix.getValue().getChunk(offset);
		}
	}

	@Override
	public AVector<T> subVector(long start, long length) {
		checkRange(start, length);
		if (length == count) return this;

		if (prefix == null) {
			int len = Utils.checkedInt(length);
			@SuppressWarnings("unchecked")
			Ref<T>[] newItems = new Ref[len];
			System.arraycopy(items, Utils.checkedInt(start), newItems, 0, len);
			return new VectorLeaf<T>(newItems, null, length);
		} else {
			long tc = prefixLength();
			if (start >= tc) {
				return this.withPrefix(null).subVector(start - tc, length);
			}

			AVector<T> tv = prefix.getValue();
			if ((start + length) <= tc) {
				return tv.subVector(start, length);
			} else {
				long split = tc - start;
				return tv.subVector(start, split).concat(this.withPrefix(null).subVector(0, length - split));
			}
		}
	}

	@Override
	public AVector<T> next() {
		if (count <= 1) return null;
		return slice(1, count - 1);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (prefix != null) {
			// if we have a prefix, should be 1..15 elements only
			if (count == Vectors.CHUNK_SIZE) {
				throw new InvalidDataException("Full ListVector with prefix? This is not right...", this);
			}

			if (count == 0) {
				throw new InvalidDataException("Empty ListVector with prefix? This is not right...", this);
			}

			AVector<T> tv = prefix.getValue();
			if (prefixLength() != tv.count()) {
				throw new InvalidDataException("Expected prefix length: " + prefixLength() + " but found " + tv.count(),
						this);
			}
			tv.validate();
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((count > 0) && (items.length == 0)) throw new InvalidDataException("Should be items present!", this);
		if (!isCanonical()) throw new InvalidDataException("Not a canonical ListVector!", this);
	}

}