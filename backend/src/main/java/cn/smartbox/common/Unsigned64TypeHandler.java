package cn.smartbox.common;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.*;
import org.apache.ibatis.type.*;
@MappedTypes(BigInteger.class)
@MappedJdbcTypes(value=JdbcType.DECIMAL,includeNullJdbcType=true)
public class Unsigned64TypeHandler extends BaseTypeHandler<BigInteger> {
    public static final BigInteger MAX=new BigInteger("18446744073709551615");
    public static BigInteger checked(BigInteger value) {
        if(value.signum()<0||value.compareTo(MAX)>0)throw new IllegalArgumentException("uint64 outside storage range");return value;
    }
    @Override public void setNonNullParameter(PreparedStatement p,int i,BigInteger v,JdbcType type)throws SQLException{p.setBigDecimal(i,new BigDecimal(checked(v)));}
    private BigInteger read(BigDecimal value)throws SQLException {
        if(value==null)return null;
        try{return checked(value.toBigIntegerExact());}catch(ArithmeticException|IllegalArgumentException e){throw new SQLException("Invalid uint64 stored value");}
    }
    @Override public BigInteger getNullableResult(ResultSet r,String c)throws SQLException{return read(r.getBigDecimal(c));}
    @Override public BigInteger getNullableResult(ResultSet r,int c)throws SQLException{return read(r.getBigDecimal(c));}
    @Override public BigInteger getNullableResult(CallableStatement s,int c)throws SQLException{return read(s.getBigDecimal(c));}
}
