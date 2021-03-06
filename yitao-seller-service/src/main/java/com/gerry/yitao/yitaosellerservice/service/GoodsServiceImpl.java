package com.gerry.yitao.yitaosellerservice.service;

import com.alibaba.dubbo.config.annotation.Service;
import com.gerry.yitao.common.exception.ServiceException;
import com.gerry.yitao.domain.*;
import com.gerry.yitao.entity.PageResult;
import com.gerry.yitao.mapper.*;
import com.gerry.yitao.seller.bo.SeckillParameter;
import com.gerry.yitao.seller.dto.CartDto;
import com.gerry.yitao.seller.service.GoodsService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import tk.mybatis.mapper.entity.Example;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品业务类
 */
@Slf4j
@Service(timeout = 30000)
public class GoodsServiceImpl implements GoodsService {

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private BrandMapper brandMapper;

    @Autowired
    private SpuDetailMapper spuDetailMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private AmqpTemplate amqpTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "yt:seckill:stock";

    @Override
    public PageResult<Spu> querySpuByPage(Integer page, Integer rows, String key, Boolean saleable) {
        //分页
        PageHelper.startPage(page, rows);

        Example example = new Example(Spu.class);
        Example.Criteria criteria = example.createCriteria();

        if (StringUtils.isNotBlank(key)) {
            criteria.andLike("title", "%" + key + "%");
        }
        if (saleable != null) {
            criteria.orEqualTo("saleable", saleable);
        }
        //默认以上一次更新时间排序
        example.setOrderByClause("last_update_time desc");

        //只查询未删除的商品
        criteria.andEqualTo("valid", 1);

        //查询
        List<Spu> spuList = spuMapper.selectByExample(example);

        if (CollectionUtils.isEmpty(spuList)) {
            throw new ServiceException("查询的商品不存在");
        }
        //对查询结果中的分类名和品牌名进行处理
        handleCategoryAndBrand(spuList);

        PageInfo<Spu> pageInfo = new PageInfo<>(spuList);

        return new PageResult<>(pageInfo.getTotal(), spuList);
    }

    @Override
    public SpuDetail querySpuDetailBySpuId(Long spuId) {
        SpuDetail spuDetail = spuDetailMapper.selectByPrimaryKey(spuId);
        if (spuDetail == null) {
            throw new ServiceException("查询的商品不存在");
        }
        return spuDetail;
    }

    @Override
    public List<Sku> querySkuBySpuId(Long spuId) {
        Sku sku = new Sku();
        sku.setSpuId(spuId);
        List<Sku> skuList = skuMapper.select(sku);
        if (CollectionUtils.isEmpty(skuList)) {
            throw new ServiceException("查询的商品的SKU失败");
        }

        //查询库存
        for (Sku sku1 : skuList) {
            sku1.setStock(stockMapper.selectByPrimaryKey(sku1.getId()).getStock());
        }
        return skuList;
    }

    @Transactional
    @Override
    public void deleteGoodsBySpuId(Long spuId) {
        if (spuId == null) {
            throw new ServiceException("请传入查询的SPU编号");
        }
        //删除spu,把spu中的valid字段设置成false
        Spu spu = new Spu();
        spu.setId(spuId);
        spu.setValid(false);
        spu.setSaleable(false);
        int count = spuMapper.updateByPrimaryKeySelective(spu);
        if (count == 0) {
            throw new ServiceException("删除SPU失败");
        }

        //发送消息
        sendMessage(spuId, "delete");
    }

    @Transactional
    @Override
    public void addGoods(Spu spu) {
        //添加商品要添加四个表 spu, spuDetail, sku, stock四张表
        spu.setSaleable(true);
        spu.setValid(true);
        spu.setCreateTime(new Date());
        spu.setLastUpdateTime(spu.getCreateTime());
        //插入数据
        int count = spuMapper.insert(spu);
        if (count != 1) {
            throw new ServiceException("添加商品信息失败");
        }
        //插入spuDetail数据
        SpuDetail spuDetail = spu.getSpuDetail();
        spuDetail.setSpuId(spu.getId());
        count = spuDetailMapper.insert(spuDetail);
        if (count != 1) {
            throw new ServiceException("添加商品详情数据失败");
        }

        //插入sku和库存
        saveSkuAndStock(spu);

        //发送消息
        sendMessage(spu.getId(), "insert");

    }

    @Transactional
    @Override
    public void updateGoods(Spu spu) {
        if (spu.getId() == 0) {
            throw new ServiceException("请传入查询的商品ID");
        }
        //首先查询sku
        Sku sku = new Sku();
        sku.setSpuId(spu.getId());
        List<Sku> skuList = skuMapper.select(sku);
        if (!CollectionUtils.isEmpty(skuList)) {
            //删除所有sku
            skuMapper.delete(sku);
            //删除库存
            List<Long> ids = skuList.stream()
                    .map(Sku::getId)
                    .collect(Collectors.toList());
            stockMapper.deleteByIdList(ids);
        }
        //更新数据库  spu  spuDetail
        spu.setLastUpdateTime(new Date());
        //更新spu spuDetail
        int count = spuMapper.updateByPrimaryKeySelective(spu);
        if (count == 0) {
            throw new ServiceException("更新商品信息失败");
        }


        SpuDetail spuDetail = spu.getSpuDetail();
        spuDetail.setSpuId(spu.getId());
        count = spuDetailMapper.updateByPrimaryKeySelective(spuDetail);
        if (count == 0) {
            throw new ServiceException("更新商品详情信息失败");
        }

        //更新sku和stock
        saveSkuAndStock(spu);

        //发送消息
        sendMessage(spu.getId(), "update");
    }

    @Override
    public void handleSaleable(Spu spu) {
        spu.setSaleable(!spu.getSaleable());
        int count = spuMapper.updateByPrimaryKeySelective(spu);
        if (count != 1) {
            throw new ServiceException("商品上架失败");
        }

        //发送消息
        sendMessage(spu.getId(), "update");
    }

    @Override
    public Spu querySpuBySpuId(Long spuId) {
        //根据spuId查询spu
        Spu spu = spuMapper.selectByPrimaryKey(spuId);

        //查询spuDetail
        SpuDetail detail = querySpuDetailBySpuId(spuId);

        //查询skus
        List<Sku> skus = querySkuBySpuId(spuId);

        spu.setSpuDetail(detail);
        spu.setSkus(skus);

        return spu;

    }

    @Override
    public List<Sku> querySkusByIds(List<Long> ids) {
        List<Sku> skus = skuMapper.selectByIdList(ids);
        if (CollectionUtils.isEmpty(skus)) {
            throw new ServiceException("查询");
        }
        //填充库存
        fillStock(ids, skus);
        return skus;
    }

    // @LcnTransaction
    public void decreaseStock(List<CartDto> cartDtos) {
        for (CartDto cartDto : cartDtos) {
            int count = stockMapper.decreaseStock(cartDto.getSkuId(), cartDto.getNum());
            if (count != 1) {
                throw new ServiceException("扣减库存失败");
            }
        }
    }

    private void fillStock(List<Long> ids, List<Sku> skus) {
        //批量查询库存
        List<Stock> stocks = stockMapper.selectByIdList(ids);
        if (CollectionUtils.isEmpty(stocks)) {
            throw new ServiceException("保存库存失败");
        }
        //首先将库存转换为map，key为sku的ID
        Map<Long, Integer> map = stocks.stream().collect(Collectors.toMap(s -> s.getSkuId(), s -> s.getStock()));

        //遍历skus，并填充库存
        for (Sku sku : skus) {
            sku.setStock(map.get(sku.getId()));
        }
    }


    /**
     * 保存sku和库存
     *
     * @param spu
     */
    private void saveSkuAndStock(Spu spu) {
        List<Sku> skuList = spu.getSkus();
        List<Stock> stocks = new ArrayList<>();

        for (Sku sku : skuList) {
            sku.setSpuId(spu.getId());
            sku.setCreateTime(new Date());
            sku.setLastUpdateTime(sku.getCreateTime());
            int count = skuMapper.insert(sku);
            if (count != 1) {
                throw new ServiceException("保存SPU对应的SKU失败");
            }

            Stock stock = new Stock();
            stock.setSkuId(sku.getId());
            stock.setStock(sku.getStock());
            stocks.add(stock);
        }
        //批量插入库存数据
        int count = stockMapper.insertList(stocks);
        if (count == 0) {
            throw new ServiceException("保存商品的SKU库存失败");
        }
    }


    /**
     * 处理商品分类名和品牌名
     *
     * @param spuList
     */
    private void handleCategoryAndBrand(List<Spu> spuList) {
        for (Spu spu : spuList) {
            //根据spu中的分类ids查询分类名
            List<String> nameList = categoryMapper.selectByIdList(Arrays.asList(spu.getCid1(), spu.getCid2(), spu.getCid3()))
                    .stream()
                    .map(Category::getName)
                    .collect(Collectors.toList());
            //对分类名进行处理
            spu.setCname(StringUtils.join(nameList, "/"));

            //查询品牌
            spu.setBname(brandMapper.selectByPrimaryKey(spu.getBrandId()).getName());
        }
    }

    /**
     * 封装发送到消息队列的方法
     *
     * @param id
     * @param type
     */
    private void sendMessage(Long id, String type) {
        try {
            amqpTemplate.convertAndSend("item." + type, id);
            System.out.println("发送商品"+type+"操作信息.......");
        } catch (Exception e) {
            log.error("{}商品消息发送异常，商品ID：{}", type, id, e);
        }
    }

    ///////////////////////// 秒杀 ////////////////////////
    /**
     * 查询秒杀商品
     * @return
     */
    @Override
    public List<SeckillGoods> querySeckillGoods() {
        Example example = new Example(SeckillGoods.class);
        // 可以秒杀
        example.createCriteria().andEqualTo("enable",true);

        List<SeckillGoods> list = this.seckillMapper.selectByExample(example);
        list.forEach(goods -> {
            Stock stock = this.stockMapper.selectByPrimaryKey(goods.getSkuId());
            goods.setStock(stock.getSeckillStock());
            goods.setSeckillTotal(stock.getSeckillTotal());
        });
        return list;
    }

    /**
     * 添加秒杀商品
     * @param seckillParameter
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillGoods(SeckillParameter seckillParameter) throws ParseException {
        SimpleDateFormat sdf =  new SimpleDateFormat( "yyyy-MM-dd HH:mm" );
        //1.根据sku_id查询商品
        Long id = seckillParameter.getId();
        Sku sku = this.skuMapper.selectByPrimaryKey(id);
        //2.插入到秒杀商品表中
        SeckillGoods seckillGoods = new SeckillGoods();
        seckillGoods.setEnable(true);
        seckillGoods.setStartTime(sdf.parse(seckillParameter.getStartTime().trim()));
        seckillGoods.setEndTime(sdf.parse(seckillParameter.getEndTime().trim()));
        seckillGoods.setImage(sku.getImages());
        seckillGoods.setSkuId(sku.getId());
        seckillGoods.setStock(seckillParameter.getCount());
        seckillGoods.setTitle(sku.getTitle());
        seckillGoods.setSeckillPrice(sku.getPrice()*seckillParameter.getDiscount());
        this.seckillMapper.insert(seckillGoods);
        //3.更新对应的库存信息，tb_stock
        Stock stock = stockMapper.selectByPrimaryKey(sku.getId());

        // 判断库存是否充足
        if (stock.getStock() - seckillGoods.getStock() < 0 ) {
            throw new ServiceException("参与秒杀的库存不足");
        }

        if (stock != null) {
            stock.setSeckillStock(stock.getSeckillStock() != null ? stock.getSeckillStock() + seckillParameter.getCount() : seckillParameter.getCount());
            stock.setSeckillTotal(stock.getSeckillTotal() != null ? stock.getSeckillTotal() + seckillParameter.getCount() : seckillParameter.getCount());
            stock.setStock(stock.getStock() - seckillParameter.getCount());
            this.stockMapper.updateByPrimaryKeySelective(stock);
        }else {
            log.info("更新库存失败！");
        }

        //4.更新redis中的秒杀库存(预热到redis中)
        updateSeckillStock();
    }



    /**
     * 更新秒杀商品数量
     * @throws Exception
     */
    public void updateSeckillStock(){
        //1.查询可以秒杀的商品
        List<SeckillGoods> seckillGoods = this.querySeckillGoods();
        if (seckillGoods == null || seckillGoods.size() == 0){
            return;
        }
        BoundHashOperations<String,Object,Object> hashOperations = this.stringRedisTemplate.boundHashOps(KEY_PREFIX);
        if (hashOperations.hasKey(KEY_PREFIX)){
            hashOperations.delete(KEY_PREFIX);
        }
        seckillGoods.forEach(goods -> hashOperations.put(goods.getSkuId().toString(),goods.getStock().toString()));
    }

}
