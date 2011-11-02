package Top {

import Chisel._
import Node._;
import Constants._;
import scala.math._;

// interface between D$ and processor
class ioDmem(view: List[String] = null) extends Bundle(view) {
  val req_val   = Bool('input);
  val req_rdy   = Bool('output);
  val req_cmd   = Bits(4, 'input);
  val req_type  = Bits(3, 'input);
  val req_addr  = UFix(32, 'input);
  val req_data  = Bits(64, 'input);
  val req_tag   = Bits(12, 'input);
  val resp_val  = Bool('output);
  val resp_data = Bits(64, 'output);
  val resp_tag  = Bits(12, 'output);
}

// interface between D$ and memory
class ioDcache(view: List[String] = null) extends Bundle(view) {
  val req_addr  = UFix(32, 'input);
  val req_tag   = UFix(3, 'input);
  val req_val   = Bool('input);
  val req_rdy   = Bool('output);
  val req_wdata = Bits(128, 'input);
  val req_rw    = Bool('input);
  val resp_data = Bits(128, 'output);
  val resp_tag  = Bits(3, 'output);
  val resp_val  = Bool('output);
}

class ioDCacheDM extends Bundle() {
  val cpu = new ioDmem();
  val mem = new ioDcache().flip();
}

// state machine to flush (write back dirty lines, invalidate clean ones) the D$
class rocketDCacheDM_flush(lines: Int, addrbits: Int) extends Component {
  val io = new ioDCacheDM();
//   val dcache = new rocketDCacheDM(lines, addrbits);
  val dcache = new rocketDCacheDM_1C(lines, addrbits);
  
  val indexbits = ceil(log10(lines)/log10(2)).toInt;
  val offsetbits = 6;
  val tagmsb    = addrbits - 1;
  val taglsb    = indexbits+offsetbits;
  val indexmsb  = taglsb-1;
  val indexlsb  = offsetbits;
  val offsetmsb = indexlsb-1;
  val offsetlsb = 3;
  
  val flush_count = Reg(resetVal = UFix(0, indexbits));
  val flush_resp_count = Reg(resetVal = UFix(0, indexbits));
  val flushing = Reg(resetVal = Bool(false));
  val flush_waiting = Reg(resetVal = Bool(false));
  val r_cpu_req_tag = Reg(resetVal = Bits(0, 12));

  when (io.cpu.req_val && io.cpu.req_rdy && (io.cpu.req_cmd === M_FLA)) 
  { 
    r_cpu_req_tag <== io.cpu.req_tag;
    flushing <== Bool(true);
    flush_waiting <== Bool(true);
  }
  
  when (dcache.io.cpu.req_rdy && 
        (flush_count === ~Bits(0, indexbits))) { flushing <== Bool(false); }
  when (dcache.io.cpu.resp_val && 
        (dcache.io.cpu.resp_tag === r_cpu_req_tag) &&
        (flush_resp_count === ~Bits(0, indexbits))) { flush_waiting <== Bool(false); }
  
  when (flushing && dcache.io.cpu.req_rdy) { flush_count <== flush_count + UFix(1,1); }
  when (flush_waiting && dcache.io.cpu.resp_val && (dcache.io.cpu.resp_tag === r_cpu_req_tag))
  { flush_resp_count <== flush_resp_count + UFix(1,1); }
  
  dcache.io.cpu.req_val   := (io.cpu.req_val && (io.cpu.req_cmd != M_FLA) && !flush_waiting) || flushing;
  dcache.io.cpu.req_cmd    := Mux(flushing, M_FLA, io.cpu.req_cmd);
  dcache.io.cpu.req_addr  := Mux(flushing, Cat(Bits(0,tagmsb-taglsb+1), flush_count, Bits(0,offsetbits)).toUFix, io.cpu.req_addr);
  dcache.io.cpu.req_tag   := Mux(flushing, r_cpu_req_tag, io.cpu.req_tag);
  dcache.io.cpu.req_type  := io.cpu.req_type;
  dcache.io.cpu.req_data  ^^ io.cpu.req_data;
  dcache.io.mem           ^^ io.mem;

  io.cpu.req_rdy   := dcache.io.cpu.req_rdy && !flush_waiting;
  io.cpu.resp_data := dcache.io.cpu.resp_data;
  io.cpu.resp_tag  := dcache.io.cpu.resp_tag;
  io.cpu.resp_val  := dcache.io.cpu.resp_val & 
    !(flush_waiting && (io.cpu.resp_tag === r_cpu_req_tag) && (flush_count != ~Bits(0, addrbits)));
  
}

// basic direct mapped data cache, 2 cycle read latency
// parameters :
//    lines = # of cache lines
//    addr_bits = address width (word addressable) bits
//    64 bit wide cpu port, 128 bit wide memory port, 64 byte cachelines
/*
class rocketDCacheDM(lines: Int, addrbits: Int) extends Component {
  val io = new ioDCacheDM();
  
  val indexbits = ceil(log10(lines)/log10(2)).toInt;
  val offsetbits = 6;
  val tagmsb    = addrbits - 1;
  val taglsb    = indexbits+offsetbits;
  val indexmsb  = taglsb-1;
  val indexlsb  = offsetbits;
  val offsetmsb = indexlsb-1;
  val offsetlsb = 3;
  
  val s_reset :: s_ready :: s_start_writeback :: s_writeback :: s_req_refill :: s_refill :: s_resolve_miss :: Nil = Enum(7) { UFix() };
  val state = Reg(resetVal = s_reset);
  
  val r_cpu_req_addr   = Reg(Bits(0, addrbits));
  val r_r_cpu_req_addr = Reg(r_cpu_req_addr);
  val r_cpu_req_val    = Reg(Bool(false));
  val r_cpu_req_data   = Reg(Bits(0,64));
  val r_cpu_req_cmd     = Reg(Bits(0,4));
  val r_cpu_req_wmask  = Reg(Bits(0,8));
  val r_cpu_req_tag    = Reg(Bits(0,12));
  val r_cpu_resp_tag   = Reg(r_cpu_req_tag);
  val r_cpu_resp_val   = Reg(Bool(false));

  when (io.cpu.req_val && io.cpu.req_rdy) { 
      r_cpu_req_addr  <== io.cpu.req_addr;
      r_cpu_req_data  <== io.cpu.req_data;
      r_cpu_req_cmd    <== io.cpu.req_cmd;
      r_cpu_req_wmask <== io.cpu.req_wmask;
      r_cpu_req_tag   <== io.cpu.req_tag; }
      
  val req_load  = (r_cpu_req_cmd === M_XRD);
  val req_store = (r_cpu_req_cmd === M_XWR);
  val req_flush = (r_cpu_req_cmd === M_FLA);
      
  when (io.cpu.req_rdy) { r_cpu_req_val <== io.cpu.req_val; }
  otherwise { r_cpu_req_val <== Bool(false); }

  // counter
  val rr_count = Reg(resetVal = UFix(0,2));
  val rr_count_next = rr_count + UFix(1);
  when (((state === s_refill) && io.mem.resp_val) || ((state === s_writeback) && io.mem.req_rdy)) 
      { rr_count <== rr_count_next; }

  // tag array
  val tag_we    = (state === s_resolve_miss);
  val tag_waddr = r_cpu_req_addr(indexmsb, indexlsb).toUFix;
  val tag_wdata = r_cpu_req_addr(tagmsb, taglsb);
  val tag_array = Mem(lines, tag_we, tag_waddr, tag_wdata);
  val tag_raddr = Mux((state === s_ready), io.cpu.req_addr(indexmsb, indexlsb).toUFix, r_cpu_req_addr(indexmsb, indexlsb).toUFix);
  val tag_rdata = Reg(tag_array.read(tag_raddr));
  
  // valid bit array
  val vb_array = Reg(resetVal = Bits(0, lines));
  val vb_rdata = Reg(vb_array(tag_raddr));
  when (tag_we && !req_flush) { vb_array <== vb_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(1,1)); }
  when (tag_we &&  req_flush) { vb_array <== vb_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(0,1)); }
          
  val tag_valid = vb_rdata.toBool;
  val tag_match = tag_valid && !req_flush && (tag_rdata === r_cpu_req_addr(tagmsb, taglsb));
  val store = ((state === s_ready) && r_cpu_req_val && req_store && tag_match ) ||
              ((state === s_resolve_miss) && req_store);           

  // dirty bit array
  val db_array  = Reg(resetVal = Bits(0, lines));
  val db_rdata  = Reg(db_array(tag_raddr));
  val tag_dirty = db_rdata.toBool;
  when (store)  { db_array <== db_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(1,1)); }
  when (tag_we) { db_array <== db_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(0,1)); }

  // data array
  val data_array_we = ((state === s_refill) && io.mem.resp_val) || store;
  val data_array_waddr = Mux((state === s_refill), 
  							 Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix, 
  							 r_cpu_req_addr(indexmsb, offsetmsb-1).toUFix);
  							 
  val data_array_wdata = Mux((state === s_refill), io.mem.resp_data, Cat(r_cpu_req_data, r_cpu_req_data));
  
  val req_wmask_expand = Cat(Fill(8, r_cpu_req_wmask(7)),
  							 Fill(8, r_cpu_req_wmask(6)),
  							 Fill(8, r_cpu_req_wmask(5)),
  							 Fill(8, r_cpu_req_wmask(4)),
  							 Fill(8, r_cpu_req_wmask(3)),
  							 Fill(8, r_cpu_req_wmask(2)),
  							 Fill(8, r_cpu_req_wmask(1)),
  							 Fill(8, r_cpu_req_wmask(0)));
  							 
  val store_wmask = Mux(r_cpu_req_addr(offsetlsb).toBool, 
  						Cat(req_wmask_expand, Bits(0,64)),
  						Cat(Bits(0,64), req_wmask_expand));
                                    
  val data_array_wmask = Mux((state === s_refill), ~Bits(0,128), store_wmask);  
  val data_array       = Mem(lines*4, data_array_we, data_array_waddr, data_array_wdata, wrMask = data_array_wmask, resetVal = null);
  val data_array_raddr = Mux((state === s_writeback) && io.mem.req_rdy, Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count_next).toUFix,
                             Mux((state === s_start_writeback) || (state === s_writeback), Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix,
                             r_cpu_req_addr(indexmsb, offsetmsb-1)));
  val data_array_rdata = Reg(data_array.read(data_array_raddr));
  
  // output signals
  io.cpu.req_rdy   := (state === s_ready) && (!r_cpu_req_val || tag_match);

  when ((((state === s_ready) && r_cpu_req_val && tag_match) || (state === s_resolve_miss)) && !req_store)
    { r_cpu_resp_val <== Bool(true); }
  otherwise { r_cpu_resp_val <== Bool(false); }  

  io.cpu.resp_val  := r_cpu_resp_val;
  io.cpu.resp_data := Mux(r_r_cpu_req_addr(offsetlsb).toBool, data_array_rdata(127, 64), data_array_rdata(63,0));
  io.cpu.resp_tag  := r_cpu_resp_tag;

  io.mem.req_val  := (state === s_req_refill) || (state === s_writeback);
  io.mem.req_rw   := (state === s_writeback);
  io.mem.req_wdata := data_array_rdata;
  io.mem.req_tag  := UFix(0);
  io.mem.req_addr := Mux(state === s_writeback, 
                         Cat(tag_rdata, r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix, 
                         Cat(r_cpu_req_addr(tagmsb, indexlsb), Bits(0,2)).toUFix);

  // control state machine
  switch (state) {
    is (s_reset) {
      state <== s_ready;
    }
    is (s_ready) {
      when (~r_cpu_req_val)             { state <== s_ready; }
      when (r_cpu_req_val & tag_match)  { state <== s_ready; }
      when (tag_valid & tag_dirty)      { state <== s_start_writeback; }
      when (req_flush)                  { state <== s_resolve_miss; }
      otherwise                         { state <== s_req_refill; }
    }
    is (s_start_writeback) {
      state <== s_writeback;
    }
    is (s_writeback) {
      when (io.mem.req_rdy && (rr_count === UFix(3,2))) { 
        when (req_flush) { state <== s_resolve_miss; } 
        otherwise        { state <== s_req_refill; }
      }
    }
    is (s_req_refill)
    {
      when (io.mem.req_rdy) { state <== s_refill; }
    }
    is (s_refill) {
      when (io.mem.resp_val && (rr_count === UFix(3,2))) { state <== s_resolve_miss; }
    }
    is (s_resolve_miss) {
      state <== s_ready;
    }
  }  
}
*/

class rocketDCacheDM_1C(lines: Int, addrbits: Int) extends Component {
  val io = new ioDCacheDM();
  
  val indexbits = ceil(log10(lines)/log10(2)).toInt;
  val offsetbits = 6;
  val tagmsb    = addrbits - 1;
  val taglsb    = indexbits+offsetbits;
  val indexmsb  = taglsb-1;
  val indexlsb  = offsetbits;
  val offsetmsb = indexlsb-1;
  val offsetlsb = 3;
  
  val s_reset :: s_ready :: s_replay_load :: s_start_writeback :: s_writeback :: s_req_refill :: s_refill :: s_resolve_miss :: Nil = Enum(8) { UFix() };
  val state = Reg(resetVal = s_reset);
  
  val r_cpu_req_addr   = Reg(resetVal = Bits(0, addrbits));
  val r_cpu_req_val    = Reg(resetVal = Bool(false));
  val r_cpu_req_data   = Reg(resetVal = Bits(0,64));
  val r_cpu_req_cmd    = Reg(resetVal = Bits(0,4));
  val r_cpu_req_type   = Reg(resetVal = Bits(0,3));
//   val r_cpu_req_wmask  = Reg(resetVal = Bits(0,8));
  val r_cpu_req_tag    = Reg(resetVal = Bits(0,5));

  val p_store_data   = Reg(resetVal = Bits(0,64));
  val p_store_addr   = Reg(resetVal = Bits(0,64));
  val p_store_wmask  = Reg(resetVal = Bits(0,64));
  val p_store_valid  = Reg(resetVal = Bool(false));

  val req_load  = (r_cpu_req_cmd === M_XRD);
  val req_store = (r_cpu_req_cmd === M_XWR);
  val req_flush = (r_cpu_req_cmd === M_FLA);

  when (io.cpu.req_val && io.cpu.req_rdy) { 
    r_cpu_req_addr  <== io.cpu.req_addr;
    r_cpu_req_data  <== io.cpu.req_data;
    r_cpu_req_cmd   <== io.cpu.req_cmd;
    r_cpu_req_type  <== io.cpu.req_type;
//     r_cpu_req_wmask <== io.cpu.req_wmask;
    r_cpu_req_tag   <== io.cpu.req_tag;
  }
      
  when (io.cpu.req_rdy) {
    r_cpu_req_val <== io.cpu.req_val; 
  }  
  when ((state === s_resolve_miss) && !req_load) {
    r_cpu_req_val <== Bool(false);
  }
    
  // counter
  val rr_count = Reg(resetVal = UFix(0,2));
  val rr_count_next = rr_count + UFix(1);
  when (((state === s_refill) && io.mem.resp_val) || ((state === s_writeback) && io.mem.req_rdy)) { 
    rr_count <== rr_count_next;
  }

  // tag array
//   val tag_we    = (state === s_resolve_miss);
  val tag_we    = (state === s_refill) && io.mem.req_rdy && (rr_count === UFix(3,2));
  val tag_waddr = r_cpu_req_addr(indexmsb, indexlsb).toUFix;
  val tag_wdata = r_cpu_req_addr(tagmsb, taglsb);
  val tag_array = Mem(lines, tag_we, tag_waddr, tag_wdata);
  val tag_raddr = 
    Mux((state === s_ready), io.cpu.req_addr(indexmsb, indexlsb).toUFix, 
      r_cpu_req_addr(indexmsb, indexlsb).toUFix);
  val tag_rdata = Reg(tag_array.read(tag_raddr));
  
  // valid bit array
  val vb_array = Reg(resetVal = Bits(0, lines));
  val vb_rdata = Reg(vb_array(tag_raddr));
  when (tag_we && !req_flush) { 
    vb_array <== vb_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(1,1));
  }
  when (tag_we && req_flush) {
    vb_array <== vb_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(0,1));
  }

  val tag_valid = vb_rdata.toBool;
  val tag_match = tag_valid && (tag_rdata === r_cpu_req_addr(tagmsb, taglsb));
    
  // generate write mask and store data signals based on store type and address LSBs
  val wmask_b =
    Mux(r_cpu_req_addr(2,0) === UFix(0, 3), Bits("b0000_0001", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(1, 3), Bits("b0000_0010", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(2, 3), Bits("b0000_0100", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(3, 3), Bits("b0000_1000", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(4, 3), Bits("b0001_0000", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(5, 3), Bits("b0010_0000", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(6, 3), Bits("b0100_0000", 8),
    Mux(r_cpu_req_addr(2,0) === UFix(7, 3), Bits("b1000_0000", 8),
        UFix(0, 8)))))))));

  val wmask_h =
    Mux(r_cpu_req_addr(2,1) === UFix(0, 2), Bits("b0000_0011", 8),
    Mux(r_cpu_req_addr(2,1) === UFix(1, 2), Bits("b0000_1100", 8),
    Mux(r_cpu_req_addr(2,1) === UFix(2, 2), Bits("b0011_0000", 8),
    Mux(r_cpu_req_addr(2,1) === UFix(3, 2), Bits("b1100_0000", 8),
        UFix(0, 8)))));

  val wmask_w =
    Mux(r_cpu_req_addr(2) === UFix(0, 1), Bits("b0000_1111", 8),
    Mux(r_cpu_req_addr(2) === UFix(1, 1), Bits("b1111_0000", 8),
        UFix(0, 8)));

  val wmask_d =
    Bits("b1111_1111", 8);
    
  val store_wmask =
    Mux(r_cpu_req_type === MT_B, wmask_b,
    Mux(r_cpu_req_type === MT_H, wmask_h,
    Mux(r_cpu_req_type === MT_W, wmask_w,
    Mux(r_cpu_req_type === MT_D, wmask_d,
        UFix(0, 8)))));
    
  val store_data =
    Mux(r_cpu_req_type === MT_B, Fill(8, r_cpu_req_data( 7,0)),
    Mux(r_cpu_req_type === MT_H, Fill(4, r_cpu_req_data(15,0)),
    Mux(r_cpu_req_type === MT_W, Fill(2, r_cpu_req_data(31,0)),
    Mux(r_cpu_req_type === MT_D, r_cpu_req_data,
       UFix(0, 64)))));
    
  when ((state === s_ready) && r_cpu_req_val && req_store) {
    p_store_data  <== store_data;
    p_store_addr  <== r_cpu_req_addr;
    p_store_wmask <== store_wmask;
    p_store_valid <== Bool(true);  
  }

  val addr_match    = (r_cpu_req_addr(tagmsb, offsetlsb) === p_store_addr(tagmsb, offsetlsb));
  val drain_store   = ((state === s_ready) && p_store_valid && (!r_cpu_req_val || !tag_match || !req_load || addr_match))
  val resolve_store = (state === s_resolve_miss) && req_store;
  val do_store      = drain_store | resolve_store;

  // dirty bit array
  val db_array  = Reg(resetVal = Bits(0, lines));
  val db_rdata  = Reg(db_array(tag_raddr));
  val tag_dirty = db_rdata.toBool;
  
  when (do_store)  {
    p_store_valid <== Bool(false);
    db_array <== db_array.bitSet(p_store_addr(indexmsb, indexlsb).toUFix, UFix(1,1));
  }
  when (tag_we) {
    db_array <== db_array.bitSet(r_cpu_req_addr(indexmsb, indexlsb).toUFix, UFix(0,1));
  }

  // data array
  val data_array_we = ((state === s_refill) && io.mem.resp_val) || do_store;
  val data_array_waddr = 
    Mux((state === s_refill), Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix, 
  		p_store_addr(indexmsb, offsetmsb-1).toUFix);

  val data_array_wdata = 
    Mux((state === s_refill), io.mem.resp_data,
      Cat(p_store_data, p_store_data));
  
  val p_wmask_expand = 
    Cat(Fill(8, p_store_wmask(7)),
  		Fill(8, p_store_wmask(6)),
  		Fill(8, p_store_wmask(5)),
  		Fill(8, p_store_wmask(4)),
  		Fill(8, p_store_wmask(3)),
  		Fill(8, p_store_wmask(2)),
  		Fill(8, p_store_wmask(1)),
  		Fill(8, p_store_wmask(0)));
  							 
  val da_store_wmask = 
    Mux(p_store_addr(offsetlsb).toBool, 
  		Cat(p_wmask_expand, Bits(0,64)),
  		Cat(Bits(0,64), p_wmask_expand));
                                    
  val data_array_wmask = 
    Mux((state === s_refill), ~Bits(0,128),
      da_store_wmask);  
  val data_array       = Mem(lines*4, data_array_we, data_array_waddr, data_array_wdata, wrMask = data_array_wmask, resetVal = null);
  val data_array_raddr = 
    Mux((state === s_writeback) && io.mem.req_rdy,                Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count_next).toUFix,
    Mux((state === s_start_writeback) || (state === s_writeback), Cat(r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix,
    Mux((state === s_resolve_miss) || (state === s_replay_load),  r_cpu_req_addr(indexmsb, offsetmsb-1),
      io.cpu.req_addr(indexmsb, offsetmsb-1))));
  val data_array_rdata = Reg(data_array.read(data_array_raddr));

  val ldst_conflict = r_cpu_req_val && req_load && p_store_valid && addr_match;
  
  // output signals
  io.cpu.req_rdy   := (state === s_ready) && !ldst_conflict && (!r_cpu_req_val || (tag_match && !req_flush));

  io.cpu.resp_val  := ((state === s_ready) && r_cpu_req_val && tag_match && req_load && !(p_store_valid && addr_match)) || 
                      ((state === s_resolve_miss) && req_flush);
                      
  io.cpu.resp_tag  := Cat(Bits(0,1), r_cpu_req_type, r_cpu_req_addr(2,0), r_cpu_req_tag);
  
  io.cpu.resp_data := 
    Mux(r_cpu_req_addr(offsetlsb).toBool, data_array_rdata(127, 64), 
      data_array_rdata(63,0));

  io.mem.req_val   := (state === s_req_refill) || (state === s_writeback);
  io.mem.req_rw    := (state === s_writeback);
  io.mem.req_wdata := data_array_rdata;
  io.mem.req_tag   := UFix(0);
  io.mem.req_addr  := 
    Mux(state === s_writeback, Cat(tag_rdata, r_cpu_req_addr(indexmsb, indexlsb), rr_count).toUFix, 
      Cat(r_cpu_req_addr(tagmsb, indexlsb), Bits(0,2)).toUFix);

  // control state machine
  switch (state) {
    is (s_reset) {
      state <== s_ready;
    }
    is (s_ready) {
      when (ldst_conflict) {
        state <== s_replay_load;
      }
      when (!r_cpu_req_val || tag_match) {
        state <== s_ready;
      }
      when (tag_valid & tag_dirty) {
        state <== s_start_writeback; 
      }
      when (req_flush) {
        state <== s_resolve_miss;
      }
      otherwise {
        state <== s_req_refill;
      }
    }
    is (s_replay_load) {
      state <== s_ready;
    }
    is (s_start_writeback) {
      state <== s_writeback;
    }
    is (s_writeback) {
      when (io.mem.req_rdy && (rr_count === UFix(3,2))) { 
        when (req_flush) { state <== s_resolve_miss; } 
        otherwise        { state <== s_req_refill; }
      }
    }
    is (s_req_refill)
    {
      when (io.mem.req_rdy) { state <== s_refill; }
    }
    is (s_refill) {
      when (io.mem.resp_val && (rr_count === UFix(3,2))) { state <== s_resolve_miss; }
    }
    is (s_resolve_miss) {
      state <== s_ready;
    }
  }  
}

}
