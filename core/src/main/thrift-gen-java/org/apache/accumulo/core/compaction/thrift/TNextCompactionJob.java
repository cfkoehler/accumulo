/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/**
 * Autogenerated by Thrift Compiler (0.17.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.accumulo.core.compaction.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
public class TNextCompactionJob implements org.apache.thrift.TBase<TNextCompactionJob, TNextCompactionJob._Fields>, java.io.Serializable, Cloneable, Comparable<TNextCompactionJob> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("TNextCompactionJob");

  private static final org.apache.thrift.protocol.TField JOB_FIELD_DESC = new org.apache.thrift.protocol.TField("job", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField COMPACTOR_COUNT_FIELD_DESC = new org.apache.thrift.protocol.TField("compactorCount", org.apache.thrift.protocol.TType.I32, (short)2);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new TNextCompactionJobStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new TNextCompactionJobTupleSchemeFactory();

  public @org.apache.thrift.annotation.Nullable org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob job; // required
  public int compactorCount; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    JOB((short)1, "job"),
    COMPACTOR_COUNT((short)2, "compactorCount");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // JOB
          return JOB;
        case 2: // COMPACTOR_COUNT
          return COMPACTOR_COUNT;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    @Override
    public short getThriftFieldId() {
      return _thriftId;
    }

    @Override
    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __COMPACTORCOUNT_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.JOB, new org.apache.thrift.meta_data.FieldMetaData("job", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob.class)));
    tmpMap.put(_Fields.COMPACTOR_COUNT, new org.apache.thrift.meta_data.FieldMetaData("compactorCount", org.apache.thrift.TFieldRequirementType.DEFAULT, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(TNextCompactionJob.class, metaDataMap);
  }

  public TNextCompactionJob() {
  }

  public TNextCompactionJob(
    org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob job,
    int compactorCount)
  {
    this();
    this.job = job;
    this.compactorCount = compactorCount;
    setCompactorCountIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public TNextCompactionJob(TNextCompactionJob other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetJob()) {
      this.job = new org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob(other.job);
    }
    this.compactorCount = other.compactorCount;
  }

  @Override
  public TNextCompactionJob deepCopy() {
    return new TNextCompactionJob(this);
  }

  @Override
  public void clear() {
    this.job = null;
    setCompactorCountIsSet(false);
    this.compactorCount = 0;
  }

  @org.apache.thrift.annotation.Nullable
  public org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob getJob() {
    return this.job;
  }

  public TNextCompactionJob setJob(@org.apache.thrift.annotation.Nullable org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob job) {
    this.job = job;
    return this;
  }

  public void unsetJob() {
    this.job = null;
  }

  /** Returns true if field job is set (has been assigned a value) and false otherwise */
  public boolean isSetJob() {
    return this.job != null;
  }

  public void setJobIsSet(boolean value) {
    if (!value) {
      this.job = null;
    }
  }

  public int getCompactorCount() {
    return this.compactorCount;
  }

  public TNextCompactionJob setCompactorCount(int compactorCount) {
    this.compactorCount = compactorCount;
    setCompactorCountIsSet(true);
    return this;
  }

  public void unsetCompactorCount() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __COMPACTORCOUNT_ISSET_ID);
  }

  /** Returns true if field compactorCount is set (has been assigned a value) and false otherwise */
  public boolean isSetCompactorCount() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __COMPACTORCOUNT_ISSET_ID);
  }

  public void setCompactorCountIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __COMPACTORCOUNT_ISSET_ID, value);
  }

  @Override
  public void setFieldValue(_Fields field, @org.apache.thrift.annotation.Nullable java.lang.Object value) {
    switch (field) {
    case JOB:
      if (value == null) {
        unsetJob();
      } else {
        setJob((org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob)value);
      }
      break;

    case COMPACTOR_COUNT:
      if (value == null) {
        unsetCompactorCount();
      } else {
        setCompactorCount((java.lang.Integer)value);
      }
      break;

    }
  }

  @org.apache.thrift.annotation.Nullable
  @Override
  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case JOB:
      return getJob();

    case COMPACTOR_COUNT:
      return getCompactorCount();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  @Override
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case JOB:
      return isSetJob();
    case COMPACTOR_COUNT:
      return isSetCompactorCount();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that instanceof TNextCompactionJob)
      return this.equals((TNextCompactionJob)that);
    return false;
  }

  public boolean equals(TNextCompactionJob that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_job = true && this.isSetJob();
    boolean that_present_job = true && that.isSetJob();
    if (this_present_job || that_present_job) {
      if (!(this_present_job && that_present_job))
        return false;
      if (!this.job.equals(that.job))
        return false;
    }

    boolean this_present_compactorCount = true;
    boolean that_present_compactorCount = true;
    if (this_present_compactorCount || that_present_compactorCount) {
      if (!(this_present_compactorCount && that_present_compactorCount))
        return false;
      if (this.compactorCount != that.compactorCount)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetJob()) ? 131071 : 524287);
    if (isSetJob())
      hashCode = hashCode * 8191 + job.hashCode();

    hashCode = hashCode * 8191 + compactorCount;

    return hashCode;
  }

  @Override
  public int compareTo(TNextCompactionJob other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.compare(isSetJob(), other.isSetJob());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetJob()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.job, other.job);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.compare(isSetCompactorCount(), other.isSetCompactorCount());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetCompactorCount()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.compactorCount, other.compactorCount);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
  @Override
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  @Override
  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  @Override
  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("TNextCompactionJob(");
    boolean first = true;

    sb.append("job:");
    if (this.job == null) {
      sb.append("null");
    } else {
      sb.append(this.job);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("compactorCount:");
    sb.append(this.compactorCount);
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (job != null) {
      job.validate();
    }
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class TNextCompactionJobStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    @Override
    public TNextCompactionJobStandardScheme getScheme() {
      return new TNextCompactionJobStandardScheme();
    }
  }

  private static class TNextCompactionJobStandardScheme extends org.apache.thrift.scheme.StandardScheme<TNextCompactionJob> {

    @Override
    public void read(org.apache.thrift.protocol.TProtocol iprot, TNextCompactionJob struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // JOB
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.job = new org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob();
              struct.job.read(iprot);
              struct.setJobIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // COMPACTOR_COUNT
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.compactorCount = iprot.readI32();
              struct.setCompactorCountIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    @Override
    public void write(org.apache.thrift.protocol.TProtocol oprot, TNextCompactionJob struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.job != null) {
        oprot.writeFieldBegin(JOB_FIELD_DESC);
        struct.job.write(oprot);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldBegin(COMPACTOR_COUNT_FIELD_DESC);
      oprot.writeI32(struct.compactorCount);
      oprot.writeFieldEnd();
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class TNextCompactionJobTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    @Override
    public TNextCompactionJobTupleScheme getScheme() {
      return new TNextCompactionJobTupleScheme();
    }
  }

  private static class TNextCompactionJobTupleScheme extends org.apache.thrift.scheme.TupleScheme<TNextCompactionJob> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, TNextCompactionJob struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetJob()) {
        optionals.set(0);
      }
      if (struct.isSetCompactorCount()) {
        optionals.set(1);
      }
      oprot.writeBitSet(optionals, 2);
      if (struct.isSetJob()) {
        struct.job.write(oprot);
      }
      if (struct.isSetCompactorCount()) {
        oprot.writeI32(struct.compactorCount);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, TNextCompactionJob struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(2);
      if (incoming.get(0)) {
        struct.job = new org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob();
        struct.job.read(iprot);
        struct.setJobIsSet(true);
      }
      if (incoming.get(1)) {
        struct.compactorCount = iprot.readI32();
        struct.setCompactorCountIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
  private static void unusedMethod() {}
}

