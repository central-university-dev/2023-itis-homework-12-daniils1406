package ru.shop.backend.search.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import ru.shop.backend.search.config.consts.TypeOfQuery;
import ru.shop.backend.search.dto.CatalogueElastic;
import ru.shop.backend.search.dto.SearchResultElastic;
import ru.shop.backend.search.model.Category;
import ru.shop.backend.search.dto.SearchResult;
import ru.shop.backend.search.dto.TypeHelpText;
import ru.shop.backend.search.model.*;
import ru.shop.backend.search.repository.ItemDbRepository;
import ru.shop.backend.search.repository.ItemRepository;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchService {
    private final ItemRepository repo;
    private final ItemDbRepository repoDb;

    private final Pageable pageable = PageRequest.of(0, 150);
    private final Pageable pageableSmall = PageRequest.of(0, 10);

    public SearchResult getSearchResult(Integer regionId, String text) {//это метод поиска вообще по любой колонке
        List<CatalogueElastic> result = null;
        if (StringUtils.isNumeric(text)) {
            Integer itemId = repoDb.findBySku(text).stream().findFirst().orElse(null);
            if (itemId == null) {
                var catalogue = getByName(text);
                if (!CollectionUtils.isEmpty(catalogue)) {
                    result = catalogue;// это значит что мы хотели найти по name потому что только он может содержать число(не считая id)
                }
            } else {
                result = getByItemId(itemId.toString());// это значит что мы хотели найти по item_id
            }
        }// БЛОК ПОВЕЩЕН ПОИСКУ ПО ЧИСЛУ, ЧИСЛО МОЖЕТ БЫТЬ ПРЕДСТАВЛЕНО ЛИБО В ВИДЕ item_id либо в виде части name, но может ничего и не найдено
        if (result == null) {
            result = getAll(text);//todo рассмотри случай когда items это null, по моему он может nullpointer породить
        }
        List<Item> items = repoDb.findByIds(regionId,
                        result.stream()
                                .flatMap(category -> category.getItems().stream())
                                .map(ItemElastic::getItemId).collect(Collectors.toList())
                ).stream()
                .map(arr -> new Item(((BigInteger) arr[2]).intValue(), arr[1].toString(), arr[3].toString(), arr[4].toString(), ((BigInteger) arr[0]).intValue(), arr[5].toString()))
                .collect(Collectors.toList());// по всем найденным itemEntity собираем item, который будет содержать цену ссылку и прочее
        String brand = "";
        if (!result.isEmpty())// если нам удалось найти хотя бы один itemEntity(ВОЗМОЖНО ЛИ ЧТО ЗДЕСЬ БУДЕ ПУСТОЙ СПИСОК)
            brand = result.get(0).getBrand().toLowerCase(Locale.ROOT);//то берем какой нибудь первый бренд
        String finalBrand = brand;
        List<Category> categories = new ArrayList<>(repoDb.findCatsByIds(items.stream().map(Item::getItemId).collect(Collectors.toList())).stream()//найти категории по id найденных предметов
                .collect(Collectors.toMap(arr -> arr[2].toString(), arr ->
                                new Category(arr[0].toString()
                                        , arr[1].toString()//todo возможно стало нечитабельнее и даже неправильно
                                        , "/cat/" + arr[2].toString() + (finalBrand.isEmpty() ? "" : "/brands/" + finalBrand)
                                        , "/cat/" + arr[3].toString(), arr[4] == null ? null : arr[4].toString())
                        , (existing, replacement) -> existing))
                .values());
        return new SearchResult(
                items,
                categories,
                !result.isEmpty() ? (List.of(new TypeHelpText(TypeOfQuery.SEE_ALSO,//todo так если result пустой то и все остальное пустым будет, может если result empty то мы вообще до сюда не добермся
                        ((result.get(0).getItems().get(0).getType() != null ? result.get(0).getItems().get(0).getType() : "") +
                                " " + (result.get(0).getBrand() != null ? result.get(0).getBrand() : "")).trim()))) : new ArrayList<>()
        );
    }

    public List<CatalogueElastic> getAll(String text) {
        return getAll(text, pageableSmall);
    }

    public List<CatalogueElastic> getAll(String text, Pageable pageable) {// походу здесь подразумевается цепочка поиска, то есть мы можем искать по нескольки вещам и если находим по какой то конкретной, то вырезаем ее из текста и идем совершать поиск по оставшейся части text
        //весь этот метод это способ понять по каким полям мы хотели осущесвтить поиск, мы просто готовим запрос для метода гет, чтобы понять по какомим полям мы хотели искать мы делаем пробные запросы, ели они хоть что то возвращают значит поле по которому ищем написано правильно и его можно отдавать методу get который уже и вернет результат поиска
        String type = "";
        List<ItemElastic> list;
        String brand = "", originalText = text;
        Long catalogueId = null;
        boolean needConvert = true;
        if(isContainErrorChar(text) || isContainErrorChar(convert(text))){
            if(isContainErrorChar(text)){
                text=convert(text);
            }
            needConvert=false;
        }
        if (text.contains(" ")) {// походу здесь сначала идет поиск по бренду, если находят то бренд вырезают из поиска и сохраняют
            for (String queryWord : text.split("\\s")) {
                list = repo.findAllByBrand(queryWord, pageable);
                if (list.isEmpty() && needConvert) {
                    list = repo.findAllByBrand(convert(text), pageable);//todo здесь ошибка в логике почему мы text передаем а не queryWord
                }
                if (!list.isEmpty()) {
                    text = text.replace(queryWord, "").trim().replace("  ", " ");//?
                    brand = list.get(0).getBrand();
                    break;
                }

            }
        }
        list = repo.findAllByType(text, pageable);
        if (list.isEmpty() && needConvert) {
            list = repo.findAllByType(convert(text), pageable);
        }
        if (!list.isEmpty()) {//значит text был/стал(в этом случае до этого в text хранился бренд) просто наименованием типа-> текст состоял из типа и бренда
            type = (list.stream().map(ItemElastic::getType).min(Comparator.comparingInt(String::length)).get());
        } else {
            for (String queryWord : text.split("\\s")) {//то есть text не просто тип,нам придется пойти смотреть text дальше, может быть поиск по типу осуществляется где то дальше?
                list = repo.findAllByType(queryWord, pageable);//todo но почему мы не вырезаем этот кусок из text?
                if (list.isEmpty() && needConvert) {
                    list = repo.findAllByType(convert(text), pageable);
                }
                if (!list.isEmpty()) {//и как раз таки это говорит о том, что в text все таки был заложен поиск по типу, этот поиск мы из text вырезаем и идеи искать дальше
                    text = text.replace(queryWord, "");
                    type = (list.stream().map(ItemElastic::getType).min(Comparator.comparingInt(String::length)).get());
                }
            }
        }
        if (brand.isEmpty()) {//значит в text не подразумевался поиск по бренду, значит попробуем поискать по каталогу
            list = repo.findByCatalogue(text, pageable);
            if (list.isEmpty() && needConvert) {
                list = repo.findByCatalogue(convert(text), pageable);
            }
            if (!list.isEmpty()) {
                catalogueId = list.get(0).getCatalogueId();
            }
        }
        text = text.trim();
        if (text.isEmpty() && !brand.isEmpty())//если поиск пуст но в нем был поиск по бренду, возвращаем найденные предметы(точно не по каталогу) по типу/бренду; здесь не будет возвращен предмет найденный по каталогу
            return Collections.singletonList(new CatalogueElastic(list.get(0).getCatalogue(), list.get(0).getCatalogueId(), null, brand));
        text += "?";//текст пуст/не пуст, но если пуст -> ТОЧНО НЕ БЫЛО ПОИСКА ПО БРЕНДУ искали по каталогу или типу
        if (brand.isEmpty()) {// если текст пуст, то точно зайдем сюда
            type += "?";
            if (catalogueId == null){// не было поиска по бренду, не было поиска по каталогу -> искать могли только по типу Т
                list = repo.findAllByType(text, type, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByType(convert(text), type, pageable);
                }
            } else {// не искали по бренду, но искали по каталогу и чему то еще(по типу?) Т+К
                list = repo.find(text, catalogueId, pageable);
                if (list.isEmpty()) {
                    list = repo.find(convert(text), catalogueId, pageable);
                }
            }

        } else {//мы искали по бренду, просто текст был не пуст, мы искали по чему то еще
            if (type.isEmpty()) {//искали по бренду Б
                list = repo.findAllByBrand(text, brand, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByBrand(convert(text), brand, pageable);
                }
            } else {// значит хотели искать по бренду и типу Б+Т
                type += "?";
                list = repo.findAllByTypeAndBrand(text, brand, type, pageable);
                if (list.isEmpty()) {
                    list = repo.findAllByTypeAndBrand(convert(text), brand, type, pageable);
                }
            }
        }

        if (list.isEmpty()) {// эту фигню не понял
            if (originalText.contains(" "))
                text = String.join(" ", text.split("\\s"));
            originalText += "?";
            list = repo.findAllNotStrong(originalText, pageable);
            if (list.isEmpty() && needConvert) {
                list = repo.findAllByTypeAndBrand(convert(originalText), brand, type, pageable);
            }
        }
        return get(list, text, brand);
    }

    private List<CatalogueElastic> get(List<ItemElastic> list, String name, String brand) {
        Map<String, List<ItemElastic>> map = new HashMap<>();
        AtomicReference<ItemElastic> searchedItem = new AtomicReference<>();
        String finalBrand = brand.isEmpty() ? null : brand;
        String replacedName = name.replace("?", "");
        list.forEach(
                i ->
                {
                    if (replacedName.equals(i.getName())) {
                        searchedItem.set(i);
                    }
                    if (replacedName.endsWith(i.getName()) && replacedName.startsWith(i.getType())) {
                        searchedItem.set(i);
                    }
                    if (!map.containsKey(i.getCatalogue())) {
                        map.put(i.getCatalogue(), new ArrayList<>());
                    }
                    map.get(i.getCatalogue()).add(i);
                }
        );//проходимся по списку предметов из ELK, и просто раскидываем его по разным каталогам, при этом находим какой-то один конкретный searchedItem зачем то
        if (searchedItem.get() != null) {//то есть если мы нашли конкретный item, то просто вернем коллекцию в которой представлен будет только он один
            ItemElastic i = searchedItem.get();
            return Collections.singletonList(new CatalogueElastic(i.getCatalogue(), i.getCatalogueId(), Collections.singletonList(i), finalBrand));
        }//то есть если конкретного не нашли нужно вернуть примерно похожие
        return map.keySet().stream().map(c ->
                new CatalogueElastic(c, map.get(c).get(0).getCatalogueId(), map.get(c), finalBrand)).collect(Collectors.toList());//todo для найденных по чслу бренд будет null проверь это поведение в оригинале
    }

    public List<CatalogueElastic> getByName(String num) {
        List<ItemElastic> list = repo.findAllByName(num, pageable);
        return get(list, num, "");
    }

    public List<CatalogueElastic> getByItemId(String itemId) {
        var entity = repo.findByItemId(itemId);
        return Collections.singletonList(new CatalogueElastic(entity.getCatalogue(), entity.getCatalogueId(), List.of(entity), entity.getBrand()));
    }

    public static String convert(String message) {
        boolean result = message.matches(".*\\p{InCyrillic}.*");
        char[] ru = {'й', 'ц', 'у', 'к', 'е', 'н', 'г', 'ш', 'щ', 'з', 'х', 'ъ', 'ф', 'ы', 'в', 'а', 'п', 'р', 'о', 'л', 'д', 'ж', 'э', 'я', 'ч', 'с', 'м', 'и', 'т', 'ь', 'б', 'ю', '.',
                ' ', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'};
        char[] en = {'q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p', '[', ']', 'a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', ';', '"', 'z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '/',
                ' ', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'};
        StringBuilder builder = new StringBuilder();

        if (result) {
            for (int i = 0; i < message.length(); i++) {
                for (int j = 0; j < ru.length; j++) {
                    if (message.charAt(i) == ru[j]) {
                        builder.append(en[j]);
                    }
                }
            }
        } else {
            for (int i = 0; i < message.length(); i++) {
                for (int j = 0; j < en.length; j++) {
                    if (message.charAt(i) == en[j]) {
                        builder.append(ru[j]);
                    }
                }
            }
        }
        return builder.toString();
    }

    private Boolean isContainErrorChar(String text) {
        if (text.contains("[") || text.contains("]") || text.contains("\"") || text.contains("/") || text.contains(";"))
            return true;
        return false;
    }

    public List<CatalogueElastic> getAllFull(String text) {
        return getAll(text, pageable);
    }

    public SearchResultElastic findBy(String text){
        if (StringUtils.isNumeric(text)) {
            Integer itemId = repoDb.findBySku(text).stream().findFirst().orElse(null);
            if (itemId == null) {
                var catalogue = getByName(text);
                if (!catalogue.isEmpty()) {
                    return new SearchResultElastic(catalogue);
                }
                return new SearchResultElastic(getAllFull(text));
            }
            try {
                return new SearchResultElastic(getByItemId(itemId.toString()));
            } catch (Exception e) {
            }
        }
        return new SearchResultElastic(getAllFull(text));
    }
}
